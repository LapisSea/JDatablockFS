package com.lapissea.cfs.type.field.fields.reflection;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.exceptions.MalformedStruct;
import com.lapissea.cfs.io.bit.*;
import com.lapissea.cfs.io.content.ContentOutputBuilder;
import com.lapissea.cfs.io.content.ContentOutputStream;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.instancepipe.StandardStructPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.objects.text.AutoText;
import com.lapissea.cfs.type.*;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.cfs.type.field.fields.NullFlagCompanyField;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.UtilL;

import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static com.lapissea.cfs.GlobalConfig.DEBUG_VALIDATION;
import static com.lapissea.cfs.type.StagedInit.STATE_DONE;

public class IOFieldDynamicInlineObject<CTyp extends IOInstance<CTyp>, ValueType> extends NullFlagCompanyField<CTyp, ValueType>{
	
	private static final StructPipe<Reference> REF_PIPE=StandardStructPipe.of(Reference.class);
	
	private final SizeDescriptor<CTyp>        descriptor;
	private       IOFieldPrimitive.FInt<CTyp> typeID;
	
	public IOFieldDynamicInlineObject(FieldAccessor<CTyp> accessor){
		super(accessor);
		
		if(getNullability()==IONullability.Mode.DEFAULT_IF_NULL){
			throw new MalformedStruct("DEFAULT_IF_NULL is not supported on dynamic fields!");
		}
		
		Type type=accessor.getGenericType(null);
		
		
		long minKnownTypeSize=Long.MAX_VALUE;
		try{
			Struct<?>         struct =Struct.ofUnknown(Utils.typeToRaw(type));
			SizeDescriptor<?> typDesc=StandardStructPipe.of(struct, STATE_DONE).getSizeDescriptor();
			minKnownTypeSize=typDesc.getMin(WordSpace.BYTE);
		}catch(IllegalArgumentException ignored){}
		
		var refDesc=REF_PIPE.getSizeDescriptor();
		
		long minSize=Math.min(refDesc.getMin(WordSpace.BYTE), minKnownTypeSize);
		
		descriptor=SizeDescriptor.Unknown.of(minSize, OptionalLong.empty(), (ioPool, prov, inst)->{
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
					yield StandardStructPipe.sizeOfUnknown(prov, inst, WordSpace.BYTE);
				}
			}
			case Enum<?> e -> Utils.bitToByte(EnumUniverse.of(e.getClass()).bitSize);
			default -> {
				var type=val.getClass();
				if(type.isArray()){
					var e  =type.getComponentType();
					var len=Array.getLength(val);
					
					var lenSize=NumberSize.bySize(len).bytes+1;
					
					var psiz=SupportedPrimitive.get(e).map(pTyp->(switch(pTyp){
						case DOUBLE, FLOAT -> pTyp.maxSize.get();
						case BOOLEAN -> Utils.bitToByte(len);
						case LONG -> NumberSize.bySize(LongStream.of((long[])val).max().orElse(0)).bytes;
						case INT -> NumberSize.bySize(IntStream.of((int[])val).max().orElse(0)).bytes;
						case SHORT, CHAR -> 2;
						case BYTE -> 1;
					})*len);
					if(psiz.isPresent()) yield psiz.get()+lenSize;
					
					if(IOInstance.isInstance(e)){
						var struct=Struct.ofUnknown(e);
						if(struct instanceof Struct.Unmanaged){
							yield Stream.of((IOInstance.Unmanaged<?>[])val).mapToLong(i->REF_PIPE.calcUnknownSize(prov, i.getReference(), WordSpace.BYTE)).sum();
						}
						
						StructPipe pip=StandardStructPipe.of(struct);
						
						if(pip.getSizeDescriptor().hasFixed()){
							yield pip.getSizeDescriptor().requireFixed(WordSpace.BYTE)*len+lenSize;
						}
						
						yield Stream.of((IOInstance<?>[])val).mapToLong(i->pip.calcUnknownSize(prov, i, WordSpace.BYTE)).sum()+lenSize;
					}
				}
				
				throw new NotImplementedException(val.getClass()+"");
			}
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
				FlagWriter.writeSingle(dest, NumberSize.FLAG_INFO, num);
				num.write(dest, value);
			}
			case String str -> AutoText.PIPE.write(provider, dest, new AutoText(str));
			case byte[] array -> {
				var num=NumberSize.bySize(array.length);
				FlagWriter.writeSingle(dest, NumberSize.FLAG_INFO, num);
				num.write(dest, array.length);
				dest.writeInts1(array);
			}
			case IOInstance.Unmanaged inst -> REF_PIPE.write(provider, dest, inst.getReference());
			case IOInstance inst -> StandardStructPipe.of(inst.getThisStruct()).write(provider, dest, inst);
			case Enum e -> FlagWriter.writeSingle(dest, EnumUniverse.of(e.getClass()), e);
			
			default -> {
				var type=val.getClass();
				if(type.isArray()){
					var len=Array.getLength(val);
					{
						var num=NumberSize.bySize(len);
						FlagWriter.writeSingle(dest, NumberSize.FLAG_INFO, num);
						num.write(dest, len);
					}
					
					var e=type.getComponentType();
					
					var pTypO=SupportedPrimitive.get(e);
					if(pTypO.isPresent()){
						var pTyp=pTypO.get();
						writePrimitiveArray(dest, val, len, pTyp);
						break;
					}
					
					if(IOInstance.isInstance(e)){
						var struct=Struct.ofUnknown(e);
						if(struct instanceof Struct.Unmanaged){
							var b=new ContentOutputBuilder();
							for(var i : (IOInstance.Unmanaged<?>[])val){
								REF_PIPE.write(provider, b, i.getReference());
							}
							b.writeTo(dest);
							break;
						}
						
						StructPipe pip=StandardStructPipe.of(struct);
						
						ContentOutputBuilder b=new ContentOutputBuilder(Math.toIntExact(pip.getSizeDescriptor().getFixed(WordSpace.BYTE).orElse(128)));
						for(var i : (IOInstance<?>[])val){
							pip.write(provider, b, i);
						}
						b.writeTo(dest);
						break;
					}
				}
				
				throw new NotImplementedException(val.getClass()+"");
			}
		}
	}
	
	private void writePrimitiveArray(ContentWriter dest, Object array, int len, SupportedPrimitive pTyp) throws IOException{
		switch(pTyp){
			case DOUBLE -> dest.writeFloats8((double[])array);
			case FLOAT -> dest.writeFloats4((float[])array);
			case BOOLEAN -> {
				try(var bitOut=new BitOutputStream(dest)){
					bitOut.writeBits((boolean[])array);
				}
			}
			case LONG -> {
				var siz=NumberSize.bySize(LongStream.of((long[])array).max().orElse(0));
				FlagWriter.writeSingle(dest, NumberSize.FLAG_INFO, siz);
				
				byte[] bb=new byte[siz.bytes*len];
				try(var io=new ContentOutputStream.BA(bb)){
					for(long l : (long[])array){
						siz.write(io, l);
					}
				}
				dest.write(bb);
			}
			case INT -> {
				var siz=NumberSize.bySize(IntStream.of((int[])array).max().orElse(0));
				FlagWriter.writeSingle(dest, NumberSize.FLAG_INFO, siz);
				
				byte[] bb=new byte[siz.bytes*len];
				try(var io=new ContentOutputStream.BA(bb)){
					for(var l : (int[])array){
						siz.write(io, l);
					}
				}
				dest.write(bb);
			}
			case SHORT -> {
				byte[] bb=new byte[len*2];
				try(var io=new ContentOutputStream.BA(bb)){
					for(var l : (short[])array){
						io.writeInt2(l&0xFFFF);
					}
				}
				dest.write(bb);
			}
			case BYTE -> throw new ShouldNeverHappenError();
		}
	}
	
	private Object readPrimitiveArray(ContentReader src, int len, SupportedPrimitive pTyp) throws IOException{
		return switch(pTyp){
			case DOUBLE -> src.readFloats8(len);
			case FLOAT -> src.readFloats4(len);
			case BOOLEAN -> {
				try(var bitIn=new BitInputStream(src, len)){
					var bools=new boolean[len];
					bitIn.readBits(bools);
					yield bools;
				}
			}
			case LONG -> {
				var siz=FlagReader.readSingle(src, NumberSize.FLAG_INFO);
				var arr=new long[len];
				for(int i=0;i<arr.length;i++){
					arr[i]=siz.read(src);
				}
				yield arr;
			}
			case INT -> {
				var siz=FlagReader.readSingle(src, NumberSize.FLAG_INFO);
				var arr=new int[len];
				for(int i=0;i<arr.length;i++){
					arr[i]=(int)siz.read(src);
				}
				yield arr;
			}
			case SHORT -> src.readInts2(len);
			case CHAR -> src.readChars2(len);
			case BYTE -> throw new ShouldNeverHappenError();
		};
	}
	
	private static long numToLong(Number integer){
		if(DEBUG_VALIDATION){
			ensureInt(integer.getClass());
		}
		return switch(integer){
			case Byte b -> b&0xFF;
			default -> integer.longValue();
		};
	}
	private static void ensureInt(Class<?> tyo){
		if(!List.<Class<? extends Number>>of(Byte.class, Short.class, Integer.class, Long.class).contains(tyo)) throw new AssertionError(tyo+" is not an integer");
	}
	
	private Object readTyp(TypeLink typDef, DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
		var typ=typDef.getTypeClass(provider.getTypeDb());
		if(typ==Boolean.class) return src.readBoolean();
		if(typ==Float.class) return (float)NumberSize.INT.readFloating(src);
		if(typ==Double.class) return NumberSize.LONG.readFloating(src);
		if(UtilL.instanceOf(typ, Number.class)){
			ensureInt(typ);
			var num    =FlagReader.readSingle(src, NumberSize.FLAG_INFO);
			var longNum=num.read(src);
			if(typ==Byte.class) return (byte)longNum;
			if(typ==Short.class) return (short)longNum;
			if(typ==Integer.class) return (int)longNum;
			if(typ==Long.class) return longNum;
			throw new NotImplementedException("Unkown integer type"+typ);
		}
		if(typ==String.class) return AutoText.PIPE.readNew(provider, src, genericContext).getData();
		if(typ==byte[].class){
			var num=FlagReader.readSingle(src, NumberSize.FLAG_INFO);
			var len=(int)num.read(src);
			return src.readInts1(len);
		}
		if(IOInstance.isUnmanaged(typ)){
			var uStruct=Struct.Unmanaged.ofUnknown(typ);
			var ref    =REF_PIPE.readNew(provider, src, genericContext);
			var inst   =uStruct.make(provider, ref, typDef);
			return inst;
		}
		if(IOInstance.isInstance(typ)){
			var struct=Struct.ofUnknown(typ);
			return readStruct(provider, src, genericContext, struct);
		}
		if(typ.isEnum()){
			var universe=EnumUniverse.ofUnknown(typ);
			return FlagReader.readSingle(src, universe);
		}
		
		if(typ.isArray()){
			int len=Math.toIntExact(FlagReader.readSingle(src, NumberSize.FLAG_INFO).read(src));
			
			var e=typ.getComponentType();
			
			var pTypO=SupportedPrimitive.get(e);
			if(pTypO.isPresent()){
				var pTyp=pTypO.get();
				return readPrimitiveArray(src, len, pTyp);
			}
			
			if(IOInstance.isInstance(e)){
				var struct=Struct.ofUnknown(e);
				if(struct instanceof Struct.Unmanaged<?> u){
					var arr=(IOInstance.Unmanaged<?>[])Array.newInstance(e, len);
					for(int i=0;i<arr.length;i++){
						var ref=REF_PIPE.readNew(provider, src, null);
						arr[i]=u.make(provider, ref, typDef);
					}
					return arr;
				}
				
				var pip=StandardStructPipe.of(struct);
				
				var arr=(IOInstance<?>[])Array.newInstance(e, len);
				for(int i=0;i<arr.length;i++){
					arr[i]=pip.readNew(provider, src, genericContext);
				}
				return arr;
			}
		}
		
		throw new NotImplementedException(typ+"");
	}
	private void skipTyp(TypeLink typDef, DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
		var        typ=typDef.getTypeClass(provider.getTypeDb());
		NumberSize siz=null;
		if(typ==Boolean.class) siz=NumberSize.BYTE;
		else if(typ==Float.class) siz=NumberSize.INT;
		else if(typ==Double.class) siz=NumberSize.LONG;
		else if(UtilL.instanceOf(typ, Number.class)){
			ensureInt(typ);
			siz=FlagReader.readSingle(src, NumberSize.FLAG_INFO);
		}
		if(siz!=null){
			src.skipExact(siz);
			return;
		}
		
		if(typ==String.class){
			AutoText.PIPE.skip(provider, src, genericContext);
			return;
		}
		if(IOInstance.isInstance(typ)){
			if(IOInstance.isUnmanaged(typ)){
				REF_PIPE.skip(provider, src, genericContext);
			}else{
				skipStruct(provider, src, genericContext, Struct.ofUnknown(typ));
			}
			return;
		}
		
		throw new NotImplementedException(typ+"");
	}
	private <T extends IOInstance<T>> T readStruct(DataProvider provider, ContentReader src, GenericContext genericContext, Struct<T> struct) throws IOException{
		var pipe=StandardStructPipe.of(struct);
		var inst=struct.make();
		pipe.read(provider, src, inst, genericContext);
		return inst;
	}
	private void skipStruct(DataProvider provider, ContentReader src, GenericContext genericContext, Struct<?> struct) throws IOException{
		var pipe=StandardStructPipe.of(struct);
		if(src.optionallySkipExact(pipe.getSizeDescriptor().getFixed(WordSpace.BYTE))){
			return;
		}
		pipe.skip(provider, src, genericContext);
	}
	
	@Override
	public void set(VarPool<CTyp> ioPool, CTyp instance, ValueType value){
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
			private IOTypeDB.TypeID getId(VarPool<CTyp> ioPool, DataProvider provider, CTyp instance, boolean record) throws IOException{
				var val=get(ioPool, instance);
				if(val==null) return new IOTypeDB.TypeID(-1, false);
				
				TypeLink def=switch(val){
					case IOInstance.Unmanaged<?> unmanaged -> unmanaged.getTypeDef();
					case IOInstance.Def<?> interf -> TypeLink.of(IOInstance.Def.unmap(interf.getClass()).orElseThrow());
					case Object inst -> TypeLink.of(inst.getClass());
				};
				
				return provider.getTypeDb().toID(def, record);
			}
			@Override
			public boolean shouldGenerate(VarPool<CTyp> ioPool, DataProvider provider, CTyp instance) throws IOException{
				var id=getId(ioPool, provider, instance, false);
				if(!id.stored()) return true;
				var writtenId=typeID.getValue(ioPool, instance);
				return id.val()!=writtenId;
			}
			@Override
			public Integer generate(VarPool<CTyp> ioPool, DataProvider provider, CTyp instance, boolean allowExternalMod) throws IOException{
				var typ=getId(ioPool, provider, instance, allowExternalMod);
				return typ.val();
			}
		});
		
		var gens=super.getGenerators();
		if(gens==null) return List.of(idGenerator);
		return Stream.concat(gens.stream(), Stream.of(idGenerator)).toList();
	}
	
	@Override
	public void write(VarPool<CTyp> ioPool, DataProvider provider, ContentWriter dest, CTyp instance) throws IOException{
		if(nullable()&&getIsNull(ioPool, instance)) return;
		var val=get(ioPool, instance);
		writeValue(provider, dest, val);
	}
	
	private TypeLink getType(VarPool<CTyp> ioPool, DataProvider provider, CTyp instance) throws IOException{
		int id=typeID.getValue(ioPool, instance);
		return provider.getTypeDb().fromID(id);
	}
	
	@Override
	public void read(VarPool<CTyp> ioPool, DataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException{
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
	public void skip(VarPool<CTyp> ioPool, DataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException{
		if(nullable()){
			if(getIsNull(ioPool, instance)){
				return;
			}
		}
		
		TypeLink typ=getType(ioPool, provider, instance);
		skipTyp(typ, provider, src, genericContext);
	}
}
