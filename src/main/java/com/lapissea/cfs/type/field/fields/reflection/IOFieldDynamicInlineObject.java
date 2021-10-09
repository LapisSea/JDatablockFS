package com.lapissea.cfs.type.field.fields.reflection;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.ChunkDataProvider;
import com.lapissea.cfs.exceptions.MalformedStructLayout;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.instancepipe.ContiguousStructPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.objects.text.AutoText;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.access.IFieldAccessor;
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
	
	private static final StructPipe<AutoText> STR_DESC=ContiguousStructPipe.of(AutoText.class);
	
	private final SizeDescriptor<CTyp> descriptor;
	
	public IOFieldDynamicInlineObject(IFieldAccessor<CTyp> accessor){
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
			minKnownTypeSize=typDesc.getMin();
		}catch(IllegalArgumentException ignored){}
		
		var refDesc=ContiguousStructPipe.of(Reference.class).getSizeDescriptor();
		
		long minSize=idSize+nullSize+Math.min(refDesc.getMin(), minKnownTypeSize);
		
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
			case String str -> STR_DESC.getSizeDescriptor().calcUnknown(new AutoText(str));
			case IOInstance inst -> ContiguousStructPipe.sizeOfUnknown(inst);
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
			case String str -> STR_DESC.write(provider, dest, new AutoText(str));
			case IOInstance inst -> ContiguousStructPipe.of(inst.getThisStruct()).write(provider, dest, inst);
			default -> throw new NotImplementedException(val.getClass()+"");
		}
	}
	private Object readTyp(Class<?> typ, ChunkDataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
		if(typ==Boolean.class) return src.readBoolean();
		if(typ==Integer.class) return (int)NumberSize.INT.read(src);
		if(typ==Float.class) return (float)NumberSize.INT.readFloating(src);
		if(typ==Long.class) return NumberSize.LONG.read(src);
		if(typ==Double.class) return NumberSize.LONG.readFloating(src);
		if(typ==String.class) return STR_DESC.readNew(provider, src, genericContext).getData();
		if(UtilL.instanceOf(typ, IOInstance.class)) return readStruct(provider, src, genericContext, Struct.ofUnknown(typ));
		throw new NotImplementedException(typ+"");
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
		
		int id=provider.getTypeDb().toID(val.getClass());
		dest.writeInt4(id);
		
		writeValue(provider, dest, val);
		
		return List.of();
	}
	
	private <T extends IOInstance<T>> T readStruct(ChunkDataProvider provider, ContentReader src, GenericContext genericContext, Struct<T> struct) throws IOException{
		var pipe=ContiguousStructPipe.of(struct);
		var inst=struct.requireEmptyConstructor().get();
		pipe.read(provider, src, inst, genericContext);
		return inst;
	}
	
	@Override
	public void read(ChunkDataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException{
		if(nullable()){
			if(!src.readBoolean()){
				set(instance, null);
				return;
			}
		}
		
		int id =src.readInt4();
		var typ=provider.getTypeDb().fromID(id);
		var val=readTyp(typ, provider, src, genericContext);
		
		//noinspection unchecked
		set(instance, (ValueType)val);
	}
	
	@Override
	public void skipRead(ChunkDataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException{
		throw NotImplementedException.infer();//TODO: implement IOFieldDynamicInlineObject.skipRead()
	}
}
