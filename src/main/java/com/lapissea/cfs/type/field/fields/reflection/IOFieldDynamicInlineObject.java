package com.lapissea.cfs.type.field.fields.reflection;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.ChunkDataProvider;
import com.lapissea.cfs.exceptions.MalformedStructLayout;
import com.lapissea.cfs.io.bit.FlagReader;
import com.lapissea.cfs.io.bit.FlagWriter;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.instancepipe.ContiguousStructPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.objects.text.AutoText;
import com.lapissea.cfs.type.*;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.UtilL;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

public class IOFieldDynamicInlineObject<CTyp extends IOInstance<CTyp>, ValueType> extends IOField<CTyp, ValueType>{
	
	private static final StructPipe<AutoText>  STR_PIPE=ContiguousStructPipe.of(AutoText.class);
	private static final StructPipe<Reference> REF_PIPE=ContiguousStructPipe.of(Reference.class);
	
	private final SizeDescriptor<CTyp> descriptor;
	
	public IOFieldDynamicInlineObject(FieldAccessor<CTyp> accessor){
		super(accessor);
		
		if(getNullability()==IONullability.Mode.DEFAULT_IF_NULL){
			throw new MalformedStructLayout("DEFAULT_IF_NULL is not supported on dynamic fields!");
		}
		
		int idSize  =4;
		int nullSize=nullable()?1:0;
		
		Type type=accessor.getGenericType(null);
		
		
		long minKnownTypeSize=Long.MAX_VALUE;
		try{
			Struct<?>         struct =Struct.ofUnknown(Utils.typeToRaw(type));
			SizeDescriptor<?> typDesc=ContiguousStructPipe.of(struct).getSizeDescriptor();
			minKnownTypeSize=typDesc.getMin(WordSpace.BYTE);
		}catch(IllegalArgumentException ignored){}
		
		var refDesc=ContiguousStructPipe.of(Reference.class).getSizeDescriptor();
		
		long minSize=idSize+nullSize+Math.min(refDesc.getMin(WordSpace.BYTE), minKnownTypeSize);
		
		descriptor=new SizeDescriptor.Unknown<>(minSize, OptionalLong.empty(), inst->{
			var val=get(inst);
			if(val==null) return nullSize;
			return nullSize+idSize+calcSize(val);
		});
	}
	
	@SuppressWarnings({"unchecked", "DuplicateBranchesInSwitch"})
	private static long calcSize(Object val){
		return switch(val){
			case Boolean ignored -> 1;
			case Integer ignored -> 4;
			case Float ignored -> 4;
			case Long ignored -> 8;
			case Double ignored -> 8;
			case String str -> STR_PIPE.getSizeDescriptor().calcUnknown(new AutoText(str), WordSpace.BYTE);
			case byte[] array -> {
				var num=NumberSize.bySize(array.length);
				yield 1+num.bytes+array.length;
			}
			case IOInstance inst -> {
				if(inst instanceof IOInstance.Unmanaged<?> u){
					yield REF_PIPE.getSizeDescriptor().calcUnknown(u.getReference(), WordSpace.BYTE);
				}else{
					yield ContiguousStructPipe.sizeOfUnknown(inst, WordSpace.BYTE);
				}
			}
			default -> throw new NotImplementedException(val.getClass()+"");
		};
	}
	@SuppressWarnings("unchecked")
	private void writeValue(ChunkDataProvider provider, ContentWriter dest, Object val) throws IOException{
		switch(val){
			case Boolean v -> dest.writeBoolean(v);
			case Integer v -> NumberSize.INT.write(dest, v);
			case Float v -> NumberSize.INT.writeFloating(dest, v);
			case Long v -> NumberSize.LONG.write(dest, v);
			case Double v -> NumberSize.LONG.writeFloating(dest, v);
			case String str -> STR_PIPE.write(provider, dest, new AutoText(str));
			case byte[] array -> {
				var num=NumberSize.bySize(array.length);
				FlagWriter.writeSingle(dest, NumberSize.BYTE, NumberSize.FLAG_INFO, false, num);
				num.write(dest, array.length);
				dest.writeInts1(array);
			}
			case IOInstance.Unmanaged inst -> {
				REF_PIPE.write(provider, dest, inst.getReference());
			}
			case IOInstance inst -> ContiguousStructPipe.of(inst.getThisStruct()).write(provider, dest, inst);
			
			default -> throw new NotImplementedException(val.getClass()+"");
		}
	}
	private Object readTyp(TypeDefinition typDef, ChunkDataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
		var typ=typDef.getTypeClass();
		if(typ==Boolean.class) return src.readBoolean();
		if(typ==Integer.class) return (int)NumberSize.INT.read(src);
		if(typ==Float.class) return (float)NumberSize.INT.readFloating(src);
		if(typ==Long.class) return NumberSize.LONG.read(src);
		if(typ==Double.class) return NumberSize.LONG.readFloating(src);
		if(typ==String.class) return STR_PIPE.readNew(provider, src, genericContext).getData();
		if(typ==byte[].class){
			var num=FlagReader.readSingle(src, NumberSize.BYTE, NumberSize.FLAG_INFO, false);
			var len=(int)num.read(src);
			return src.readInts1(len);
		}
		if(UtilL.instanceOf(typ, IOInstance.Unmanaged.class)){
			var uStruct=Struct.Unmanaged.ofUnknown(typ);
			var ref    =REF_PIPE.readNew(provider, src, genericContext);
			var inst   =uStruct.requireUnmanagedConstructor().create(provider, ref, typDef);
			return inst;
		}
		if(UtilL.instanceOf(typ, IOInstance.class)){
			var struct=Struct.ofUnknown(typ);
			return readStruct(provider, src, genericContext, struct);
		}
		throw new NotImplementedException(typ+"");
	}
	private void skipReadTyp(TypeDefinition typDef, ChunkDataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
		var        typ=typDef.getTypeClass();
		NumberSize siz=null;
		if(typ==Boolean.class) siz=NumberSize.BYTE;
		else if(typ==Integer.class||typ==Float.class) siz=NumberSize.INT;
		else if(typ==Long.class||typ==Double.class) siz=NumberSize.LONG;
		if(siz!=null){
			src.skipExact(siz);
			return;
		}
		
		if(typ==String.class){
			STR_PIPE.readNew(provider, src, genericContext);
			return;
		}
		if(UtilL.instanceOf(typ, IOInstance.class)){
			if(UtilL.instanceOf(typ, IOInstance.Unmanaged.class)){
				REF_PIPE.readNew(provider, src, genericContext);
			}else{
				skipReadStruct(provider, src, genericContext, Struct.ofUnknown(typ));
			}
		}
		
		throw new NotImplementedException(typ+"");
	}
	private <T extends IOInstance<T>> T readStruct(ChunkDataProvider provider, ContentReader src, GenericContext genericContext, Struct<T> struct) throws IOException{
		var pipe=ContiguousStructPipe.of(struct);
		var inst=struct.requireEmptyConstructor().get();
		pipe.read(provider, src, inst, genericContext);
		return inst;
	}
	private void skipReadStruct(ChunkDataProvider provider, ContentReader src, GenericContext genericContext, Struct<?> struct) throws IOException{
		var pipe =ContiguousStructPipe.of(struct);
		var fixed=pipe.getSizeDescriptor().getFixed(WordSpace.BYTE);
		if(fixed.isPresent()){
			src.skip(fixed.getAsLong());
			return;
		}
		pipe.readNew(provider, src, genericContext);
	}
	
	@Override
	public ValueType get(CTyp instance){
		ValueType value=super.get(instance);
		return switch(getNullability()){
			case NOT_NULL -> requireValNN(value);
			case NULLABLE -> value;
			case DEFAULT_IF_NULL -> throw new ShouldNeverHappenError();
		};
	}
	
	@Override
	public void set(CTyp instance, ValueType value){
		super.set(instance, switch(getNullability()){
			case DEFAULT_IF_NULL, NULLABLE -> value;
			case NOT_NULL -> Objects.requireNonNull(value);
		});
	}
	
	@Override
	public SizeDescriptor<CTyp> getSizeDescriptor(){
		return descriptor;
	}
	
	@Override
	public List<IOField<CTyp, ?>> write(ChunkDataProvider provider, ContentWriter dest, CTyp instance) throws IOException{
		var val=get(instance);
		
		if(nullable()){
			if(val==null){
				dest.writeBoolean(false);
				return List.of();
			}
			dest.writeBoolean(true);
		}
		
		TypeDefinition def;
		if(val instanceof IOInstance.Unmanaged<?> unmanaged){
			def=unmanaged.getTypeDef();
		}else{
			def=TypeDefinition.of(val.getClass());
		}
		int id=provider.getTypeDb().toID(def);
		dest.writeInt4(id);
		
		writeValue(provider, dest, val);
		
		return List.of();
	}
	
	private TypeDefinition readType(ChunkDataProvider provider, ContentReader src) throws IOException{
		int id=src.readInt4();
		return provider.getTypeDb().fromID(id);
	}
	
	@Override
	public void read(ChunkDataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException{
		Object val;
		read:
		{
			if(nullable()){
				if(!src.readBoolean()){
					val=null;
					break read;
				}
			}
			
			TypeDefinition typ=readType(provider, src);
			val=readTyp(typ, provider, src, genericContext);
		}
		//noinspection unchecked
		set(instance, (ValueType)val);
	}
	
	@Override
	public void skipRead(ChunkDataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException{
		if(nullable()){
			if(!src.readBoolean()){
				return;
			}
		}
		
		TypeDefinition typ=readType(provider, src);
		skipReadTyp(typ, provider, src, genericContext);
	}
}
