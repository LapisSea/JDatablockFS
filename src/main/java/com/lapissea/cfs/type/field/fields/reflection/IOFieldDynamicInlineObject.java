package com.lapissea.cfs.type.field.fields.reflection;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.DataProvider;
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
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.UtilL;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.stream.Stream;

public class IOFieldDynamicInlineObject<CTyp extends IOInstance<CTyp>, ValueType> extends IOField.NullFlagCompany<CTyp, ValueType>{
	
	private static final StructPipe<Reference> REF_PIPE=ContiguousStructPipe.of(Reference.class);
	
	private final SizeDescriptor<CTyp>        descriptor;
	private       IOFieldPrimitive.FInt<CTyp> typeID;
	
	public IOFieldDynamicInlineObject(FieldAccessor<CTyp> accessor){
		super(accessor);
		
		if(getNullability()==IONullability.Mode.DEFAULT_IF_NULL){
			throw new MalformedStructLayout("DEFAULT_IF_NULL is not supported on dynamic fields!");
		}
		
		Type type=accessor.getGenericType(null);
		
		
		long minKnownTypeSize=Long.MAX_VALUE;
		try{
			Struct<?>         struct =Struct.ofUnknown(Utils.typeToRaw(type));
			SizeDescriptor<?> typDesc=ContiguousStructPipe.of(struct).getSizeDescriptor();
			minKnownTypeSize=typDesc.getMin(WordSpace.BYTE);
		}catch(IllegalArgumentException ignored){}
		
		var refDesc=ContiguousStructPipe.of(Reference.class).getSizeDescriptor();
		
		long minSize=Math.min(refDesc.getMin(WordSpace.BYTE), minKnownTypeSize);
		
		descriptor=new SizeDescriptor.Unknown<>(minSize, OptionalLong.empty(), (ioPool, prov, inst)->{
			var val=get(null, inst);
			if(val==null) return 0;
			return calcSize(prov, val);
		});
	}
	
	@Override
	public void init(){
		super.init();
		typeID=declaringStruct().getFields().requireExactInt(IOFieldTools.makeGenericIDFieldName(getAccessor()));
	}
	
	@SuppressWarnings({"unchecked"})
	private static long calcSize(DataProvider prov, Object val){
		return switch(val){
			case Boolean ignored -> 1;
			case Float ignored -> 4;
			case Double ignored -> 8;
			case Number integer -> 1+NumberSize.bySize(numToLong(integer)).bytes;
			case String str -> AutoText.PIPE.calcUnknownSize(prov, new AutoText(str), WordSpace.BYTE);
			case byte[] array -> {
				var num=NumberSize.bySize(array.length);
				yield 1+num.bytes+array.length;
			}
			case IOInstance inst -> {
				if(inst instanceof IOInstance.Unmanaged<?> u){
					yield REF_PIPE.calcUnknownSize(prov, u.getReference(), WordSpace.BYTE);
				}else{
					yield ContiguousStructPipe.sizeOfUnknown(prov, inst, WordSpace.BYTE);
				}
			}
			default -> throw new NotImplementedException(val.getClass()+"");
		};
	}
	@SuppressWarnings("unchecked")
	private void writeValue(DataProvider provider, ContentWriter dest, Object val) throws IOException{
		switch(val){
			case Boolean v -> dest.writeBoolean(v);
			case Float v -> NumberSize.INT.writeFloating(dest, v);
			case Double v -> NumberSize.LONG.writeFloating(dest, v);
			case Number integer -> {
				long value=numToLong(integer);
				var  num  =NumberSize.bySize(value);
				FlagWriter.writeSingle(dest, NumberSize.FLAG_INFO, false, num);
				num.write(dest, value);
			}
			case String str -> AutoText.PIPE.write(provider, dest, new AutoText(str));
			case byte[] array -> {
				var num=NumberSize.bySize(array.length);
				FlagWriter.writeSingle(dest, NumberSize.BYTE, NumberSize.FLAG_INFO, false, num);
				num.write(dest, array.length);
				dest.writeInts1(array);
			}
			case IOInstance.Unmanaged inst -> REF_PIPE.write(provider, dest, inst.getReference());
			case IOInstance inst -> ContiguousStructPipe.of(inst.getThisStruct()).write(provider, dest, inst);
			
			default -> throw new NotImplementedException(val.getClass()+"");
		}
	}
	private static long numToLong(Number integer){
		ensureInt(integer.getClass());
		return switch(integer){
			case Byte b -> b&0xFF;
			default -> integer.longValue();
		};
	}
	private static void ensureInt(Class<?> tyo){
		assert List.<Class<? extends Number>>of(Byte.class, Short.class, Integer.class, Long.class).contains(tyo);
	}
	
	private Object readTyp(TypeLink typDef, DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
		var typ=typDef.getTypeClass(provider.getTypeDb());
		if(typ==Boolean.class) return src.readBoolean();
		if(typ==Float.class) return (float)NumberSize.INT.readFloating(src);
		if(typ==Double.class) return NumberSize.LONG.readFloating(src);
		if(UtilL.instanceOf(typ, Number.class)){
			ensureInt(typ);
			var num    =FlagReader.readSingle(src, NumberSize.FLAG_INFO, false);
			var longNum=num.read(src);
			if(typ==Byte.class) return (byte)longNum;
			if(typ==Short.class) return (short)longNum;
			if(typ==Integer.class) return (int)longNum;
			if(typ==Long.class) return longNum;
			throw new NotImplementedException("Unkown integer type"+typ);
		}
		if(typ==String.class) return AutoText.PIPE.readNew(provider, src, genericContext).getData();
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
	private void skipReadTyp(TypeLink typDef, DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
		var        typ=typDef.getTypeClass(provider.getTypeDb());
		NumberSize siz=null;
		if(typ==Boolean.class) siz=NumberSize.BYTE;
		else if(typ==Float.class) siz=NumberSize.INT;
		else if(typ==Double.class) siz=NumberSize.LONG;
		else if(UtilL.instanceOf(typ, Number.class)){
			ensureInt(typ);
			siz=FlagReader.readSingle(src, NumberSize.FLAG_INFO, false);
		}
		if(siz!=null){
			src.skipExact(siz);
			return;
		}
		
		if(typ==String.class){
			AutoText.PIPE.readNew(provider, src, genericContext);
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
	private <T extends IOInstance<T>> T readStruct(DataProvider provider, ContentReader src, GenericContext genericContext, Struct<T> struct) throws IOException{
		var pipe=ContiguousStructPipe.of(struct);
		var inst=struct.requireEmptyConstructor().get();
		pipe.read(provider, src, inst, genericContext);
		return inst;
	}
	private void skipReadStruct(DataProvider provider, ContentReader src, GenericContext genericContext, Struct<?> struct) throws IOException{
		var pipe=ContiguousStructPipe.of(struct);
		if(src.optionallySkipExact(pipe.getSizeDescriptor().getFixed(WordSpace.BYTE))){
			return;
		}
		pipe.readNew(provider, src, genericContext);
	}
	
	@Override
	public ValueType get(Struct.Pool<CTyp> ioPool, CTyp instance){
		return getNullable(ioPool, instance);
	}
	
	@Override
	public void set(Struct.Pool<CTyp> ioPool, CTyp instance, ValueType value){
		super.set(ioPool, instance, switch(getNullability()){
			case DEFAULT_IF_NULL, NULLABLE -> value;
			case NOT_NULL -> Objects.requireNonNull(value);
		});
	}
	
	@Override
	public SizeDescriptor<CTyp> getSizeDescriptor(){
		return descriptor;
	}
	
	@Override
	public List<ValueGeneratorInfo<CTyp, ?>> getGenerators(){
		var idGenerator=new ValueGeneratorInfo<>(typeID, new ValueGenerator<CTyp, Integer>(){
			private IOTypeDB.TypeID getId(Struct.Pool<CTyp> ioPool, DataProvider provider, CTyp instance, boolean record) throws IOException{
				var val=get(ioPool, instance);
				if(val==null) return new IOTypeDB.TypeID(-1, false);
				
				TypeLink def;
				if(val instanceof IOInstance.Unmanaged<?> unmanaged){
					def=unmanaged.getTypeDef();
				}else{
					def=TypeLink.of(val.getClass());
				}
				return provider.getTypeDb().toID(def, record);
			}
			@Override
			public boolean shouldGenerate(Struct.Pool<CTyp> ioPool, DataProvider provider, CTyp instance) throws IOException{
				var id=getId(ioPool, provider, instance, false);
				if(!id.stored()) return true;
				var writtenId=typeID.getValue(ioPool, instance);
				return id.val()!=writtenId;
			}
			@Override
			public Integer generate(Struct.Pool<CTyp> ioPool, DataProvider provider, CTyp instance, boolean allowExternalMod) throws IOException{
				return getId(ioPool, provider, instance, allowExternalMod).val();
			}
		});
		
		return Stream.concat(super.getGenerators().stream(), Stream.of(idGenerator)).toList();
	}
	@Override
	public void write(Struct.Pool<CTyp> ioPool, DataProvider provider, ContentWriter dest, CTyp instance) throws IOException{
		if(nullable()&&getIsNull(ioPool, instance)) return;
		var val=get(ioPool, instance);
		writeValue(provider, dest, val);
	}
	
	private TypeLink getType(Struct.Pool<CTyp> ioPool, DataProvider provider, CTyp instance) throws IOException{
		int id=typeID.getValue(ioPool, instance);
		return provider.getTypeDb().fromID(id);
	}
	
	@Override
	public void read(Struct.Pool<CTyp> ioPool, DataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException{
		Object val;
		read:
		{
			if(nullable()){
				if(getIsNull(ioPool, instance)){
					val=null;
					break read;
				}
			}
			
			TypeLink typ=getType(ioPool, provider, instance);
			val=readTyp(typ, provider, src, genericContext);
		}
		//noinspection unchecked
		set(ioPool, instance, (ValueType)val);
	}
	
	@Override
	public void skipRead(Struct.Pool<CTyp> ioPool, DataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException{
		if(nullable()){
			if(getIsNull(ioPool, instance)){
				return;
			}
		}
		
		TypeLink typ=getType(ioPool, provider, instance);
		skipReadTyp(typ, provider, src, genericContext);
	}
}
