package com.lapissea.dfs.type.field.fields.reflection;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.io.bit.BitInputStream;
import com.lapissea.dfs.io.bit.BitOutputStream;
import com.lapissea.dfs.io.bit.BitUtils;
import com.lapissea.dfs.io.bit.EnumUniverse;
import com.lapissea.dfs.io.bit.FlagReader;
import com.lapissea.dfs.io.bit.FlagWriter;
import com.lapissea.dfs.io.content.ContentOutputBuilder;
import com.lapissea.dfs.io.content.ContentOutputStream;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.io.content.ContentWriter;
import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.objects.NumberSize;
import com.lapissea.dfs.objects.Reference;
import com.lapissea.dfs.objects.text.AutoText;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.IOType;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.SupportedPrimitive;
import com.lapissea.dfs.type.WordSpace;
import com.lapissea.dfs.type.compilation.WrapperStructs;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.UtilL;

import java.io.IOException;
import java.lang.reflect.Array;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static com.lapissea.dfs.config.GlobalConfig.DEBUG_VALIDATION;

public abstract class DynamicSupport{
	
	private static final StructPipe<Reference> REF_PIPE = Reference.standardPipe();
	
	
	@SuppressWarnings({"unchecked"})
	public static long calcSize(DataProvider prov, Object val){
		return switch(val){
			case null -> 0;
			case Boolean ignored -> 1;
			case Float ignored -> 4;
			case Double ignored -> 8;
			case Number integer -> 1 + NumberSize.bySizeSigned(numToLong(integer)).bytes;
			case String str -> AutoText.PIPE.calcUnknownSize(prov, new AutoText(str), WordSpace.BYTE);
			case byte[] array -> {
				var num = NumberSize.bySize(array.length);
				yield 1 + num.bytes + array.length;
			}
			case IOInstance inst -> {
				if(inst instanceof IOInstance.Unmanaged<?> u){
					yield ChunkPointer.DYN_SIZE_DESCRIPTOR.calcUnknown(null, prov, u.getPointer(), WordSpace.BYTE);
				}else{
					yield StandardStructPipe.sizeOfUnknown(prov, inst, WordSpace.BYTE);
				}
			}
			case Enum<?> e -> BitUtils.bitsToBytes(EnumUniverse.of(e.getClass()).bitSize);
			default -> {
				var type = val.getClass();
				if(type.isArray()){
					var e   = type.getComponentType();
					var len = Array.getLength(val);
					
					var lenSize = NumberSize.bySize(len).bytes + 1;
					
					var psiz = SupportedPrimitive.get(e).map(pTyp -> (long)switch(pTyp){
						case DOUBLE, FLOAT -> pTyp.maxSize.get()*len;
						case BOOLEAN -> BitUtils.bitsToBytes(len);
						case LONG -> 1 + NumberSize.bySize(LongStream.of((long[])val).max().orElse(0)).bytes*(long)len;
						case INT -> 1 + NumberSize.bySize(IntStream.of((int[])val).max().orElse(0)).bytes*(long)len;
						case SHORT, CHAR -> 2L*len;
						case BYTE -> len;
					});
					if(psiz.isPresent()) yield psiz.get() + lenSize;
					
					if(IOInstance.isInstance(e)){
						var struct = Struct.ofUnknown(e);
						if(struct instanceof Struct.Unmanaged){
							yield Stream.of((IOInstance.Unmanaged<?>[])val).mapToLong(i -> ChunkPointer.DYN_SIZE_DESCRIPTOR.calcUnknown(null, prov, i.getPointer(), WordSpace.BYTE)).sum();
						}
						
						StructPipe pip = StandardStructPipe.of(struct);
						
						if(pip.getSizeDescriptor().hasFixed()){
							yield pip.getSizeDescriptor().requireFixed(WordSpace.BYTE)*len + lenSize;
						}
						
						yield Stream.of((IOInstance<?>[])val).mapToLong(i -> pip.calcUnknownSize(prov, i, WordSpace.BYTE)).sum() + lenSize;
					}
					
					long sum = 0;
					for(int i = 0; i<len; i++){
						sum += calcSize(prov, Array.get(val, i));
					}
					yield sum;
				}
				
				var wrapper = (WrapperStructs.WrapperRes<Object>)WrapperStructs.getWrapperStruct(type);
				if(wrapper != null){
					var obj = wrapper.constructor().apply(val);
					yield StandardStructPipe.sizeOfUnknown(prov, obj, WordSpace.BYTE);
				}
				
				throw new NotImplementedException(val.getClass() + "");
			}
		};
	}
	@SuppressWarnings("unchecked")
	public static void writeValue(DataProvider provider, ContentWriter dest, Object val) throws IOException{
		switch(val){
			case Boolean v -> dest.writeBoolean(v);
			case Float v -> NumberSize.INT.writeFloating(dest, v);
			case Double v -> NumberSize.LONG.writeFloating(dest, v);
			case Number integer -> {
				long value = numToLong(integer);
				var  num   = NumberSize.bySizeSigned(value);
				FlagWriter.writeSingle(dest, NumberSize.FLAG_INFO, num);
				num.writeSigned(dest, value);
			}
			case String str -> AutoText.PIPE.write(provider, dest, new AutoText(str));
			case byte[] array -> {
				var num = NumberSize.bySize(array.length);
				FlagWriter.writeSingle(dest, NumberSize.FLAG_INFO, num);
				num.write(dest, array.length);
				dest.writeInts1(array);
			}
			case IOInstance.Unmanaged inst -> ChunkPointer.DYN_PIPE.write(provider, dest, inst.getPointer());
			case IOInstance inst -> StandardStructPipe.of(inst.getThisStruct()).write(provider, dest, inst);
			case Enum e -> FlagWriter.writeSingle(dest, EnumUniverse.of(e.getClass()), e);
			
			default -> {
				var type = val.getClass();
				if(type.isArray()){
					var len = Array.getLength(val);
					{
						var num = NumberSize.bySize(len);
						FlagWriter.writeSingle(dest, NumberSize.FLAG_INFO, num);
						num.write(dest, len);
					}
					
					var e = type.getComponentType();
					
					var pTypO = SupportedPrimitive.get(e);
					if(pTypO.isPresent()){
						var pTyp = pTypO.get();
						writePrimitiveArray(dest, val, len, pTyp);
						break;
					}
					
					if(IOInstance.isInstance(e)){
						var struct = Struct.ofUnknown(e);
						if(struct instanceof Struct.Unmanaged){
							var b = new ContentOutputBuilder();
							for(var i : (IOInstance.Unmanaged<?>[])val){
								ChunkPointer.DYN_PIPE.write(provider, b, i.getPointer());
							}
							b.writeTo(dest);
							break;
						}
						
						StructPipe pip = StandardStructPipe.of(struct);
						
						ContentOutputBuilder b = new ContentOutputBuilder(Math.toIntExact(pip.getSizeDescriptor().getFixed(WordSpace.BYTE).orElse(128)));
						for(var i : (IOInstance<?>[])val){
							pip.write(provider, b, i);
						}
						b.writeTo(dest);
						break;
					}
					
					for(int i = 0; i<len; i++){
						writeValue(provider, dest, Array.get(val, i));
					}
					break;
				}
				
				var wrapper = (WrapperStructs.WrapperRes<Object>)WrapperStructs.getWrapperStruct(type);
				if(wrapper != null){
					var obj = wrapper.constructor().apply(val);
					var pip = StandardStructPipe.of(wrapper.struct());
					pip.write(provider, dest, obj);
					break;
				}
				
				throw new NotImplementedException(val.getClass() + "");
			}
		}
	}
	
	private static void writePrimitiveArray(ContentWriter dest, Object array, int len, SupportedPrimitive pTyp) throws IOException{
		switch(pTyp){
			case DOUBLE -> dest.writeFloats8((double[])array);
			case FLOAT -> dest.writeFloats4((float[])array);
			case BOOLEAN -> {
				try(var bitOut = new BitOutputStream(dest)){
					bitOut.writeBits((boolean[])array);
				}
			}
			case LONG -> {
				var siz = NumberSize.bySize(LongStream.of((long[])array).max().orElse(0));
				FlagWriter.writeSingle(dest, NumberSize.FLAG_INFO, siz);
				
				byte[] bb = new byte[siz.bytes*len];
				try(var io = new ContentOutputStream.BA(bb)){
					for(long l : (long[])array){
						siz.write(io, l);
					}
				}
				dest.write(bb);
			}
			case INT -> {
				var siz = NumberSize.bySize(IntStream.of((int[])array).max().orElse(0));
				FlagWriter.writeSingle(dest, NumberSize.FLAG_INFO, siz);
				
				byte[] bb = new byte[siz.bytes*len];
				try(var io = new ContentOutputStream.BA(bb)){
					for(var l : (int[])array){
						siz.write(io, l);
					}
				}
				dest.write(bb);
			}
			case SHORT -> {
				byte[] bb = new byte[len*2];
				try(var io = new ContentOutputStream.BA(bb)){
					for(var l : (short[])array){
						io.writeInt2(l&0xFFFF);
					}
				}
				dest.write(bb);
			}
			case BYTE -> throw new ShouldNeverHappenError();
		}
	}
	
	private static Object readPrimitiveArray(ContentReader src, int len, SupportedPrimitive pTyp) throws IOException{
		return switch(pTyp){
			case DOUBLE -> src.readFloats8(len);
			case FLOAT -> src.readFloats4(len);
			case BOOLEAN -> {
				try(var bitIn = new BitInputStream(src, len)){
					yield bitIn.readBits(new boolean[len]);
				}
			}
			case LONG -> {
				var siz = FlagReader.readSingle(src, NumberSize.FLAG_INFO);
				var arr = new long[len];
				for(int i = 0; i<arr.length; i++){
					arr[i] = siz.read(src);
				}
				yield arr;
			}
			case INT -> {
				var siz = FlagReader.readSingle(src, NumberSize.FLAG_INFO);
				var arr = new int[len];
				for(int i = 0; i<arr.length; i++){
					arr[i] = (int)siz.read(src);
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
		if(!List.<Class<? extends Number>>of(Byte.class, Short.class, Integer.class, Long.class).contains(tyo)) throw new AssertionError(tyo + " is not an integer");
	}
	
	public static Object readTyp(IOType typDef, DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
		var typ = typDef.getTypeClass(provider.getTypeDb());
		if(typ == Boolean.class) return src.readBoolean();
		if(typ == Float.class) return (float)NumberSize.INT.readFloating(src);
		if(typ == Double.class) return NumberSize.LONG.readFloating(src);
		if(UtilL.instanceOf(typ, Number.class)){
			ensureInt(typ);
			var num     = FlagReader.readSingle(src, NumberSize.FLAG_INFO);
			var longNum = num.readSigned(src);
			if(typ == Byte.class) return (byte)longNum;
			if(typ == Short.class) return (short)longNum;
			if(typ == Integer.class) return (int)longNum;
			if(typ == Long.class) return longNum;
			throw new NotImplementedException("Unkown integer type" + typ);
		}
		if(typ == String.class) return AutoText.PIPE.readNew(provider, src, genericContext).getData();
		if(typ == byte[].class) return src.readInts1(src.readUnsignedInt4Dynamic());
		
		if(IOInstance.isUnmanaged(typ)){
			var uStruct = Struct.Unmanaged.ofUnknown(typ);
			var ptr     = ChunkPointer.DYN_PIPE.readNew(provider, src, null);
			var ch      = ptr.dereference(provider);
			return uStruct.make(provider, ch, typDef);
		}
		if(IOInstance.isInstance(typ)){
			var struct = Struct.ofUnknown(typ);
			var pipe   = StandardStructPipe.of(struct);
			return pipe.readNew(provider, src, genericContext);
		}
		if(typ.isEnum()){
			var universe = EnumUniverse.ofUnknown(typ);
			return FlagReader.readSingle(src, universe);
		}
		
		if(typ.isArray()){
			int len = Math.toIntExact(FlagReader.readSingle(src, NumberSize.FLAG_INFO).read(src));
			
			var e = typ.getComponentType();
			
			var pTypO = SupportedPrimitive.get(e);
			if(pTypO.isPresent()){
				var pTyp = pTypO.get();
				return readPrimitiveArray(src, len, pTyp);
			}
			
			if(IOInstance.isInstance(e)){
				var struct = Struct.ofUnknown(e);
				if(struct instanceof Struct.Unmanaged<?> u){
					var arr = (IOInstance.Unmanaged<?>[])Array.newInstance(e, len);
					for(int i = 0; i<arr.length; i++){
						var ptr = ChunkPointer.DYN_PIPE.readNew(provider, src, genericContext);
						var ch  = ptr.dereference(provider);
						arr[i] = u.make(provider, ch, typDef);
					}
					return arr;
				}
				
				var pip = StandardStructPipe.of(struct);
				
				var arr = (IOInstance<?>[])Array.newInstance(e, len);
				for(int i = 0; i<arr.length; i++){
					arr[i] = pip.readNew(provider, src, genericContext);
				}
				return arr;
			}
		}
		
		var wrapper = (WrapperStructs.WrapperRes<Object>)WrapperStructs.getWrapperStruct(typ);
		if(wrapper != null){
			var pip = StandardStructPipe.of(wrapper.struct());
			var obj = pip.readNew(provider, src, genericContext);
			return obj.get();
		}
		
		throw new NotImplementedException(typ + "");
	}
	public static void skipTyp(IOType typDef, DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
		var        typ = typDef.getTypeClass(provider.getTypeDb());
		NumberSize siz = null;
		if(typ == Boolean.class) siz = NumberSize.BYTE;
		else if(typ == Float.class) siz = NumberSize.INT;
		else if(typ == Double.class) siz = NumberSize.LONG;
		else if(UtilL.instanceOf(typ, Number.class)){
			ensureInt(typ);
			siz = FlagReader.readSingle(src, NumberSize.FLAG_INFO);
		}
		if(siz != null){
			src.skipExact(siz);
			return;
		}
		
		if(typ == String.class){
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
		
		throw new NotImplementedException(typ + "");
	}
	private static void skipStruct(DataProvider provider, ContentReader src, GenericContext genericContext, Struct<?> struct) throws IOException{
		var pipe = StandardStructPipe.of(struct);
		if(src.optionallySkipExact(pipe.getSizeDescriptor().getFixed(WordSpace.BYTE))){
			return;
		}
		pipe.skip(provider, src, genericContext);
	}
}
