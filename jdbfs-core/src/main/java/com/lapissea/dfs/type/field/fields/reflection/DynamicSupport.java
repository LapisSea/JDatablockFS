package com.lapissea.dfs.type.field.fields.reflection;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.io.bit.BitInputStream;
import com.lapissea.dfs.io.bit.BitOutputStream;
import com.lapissea.dfs.io.bit.BitUtils;
import com.lapissea.dfs.io.bit.EnumUniverse;
import com.lapissea.dfs.io.bit.FlagReader;
import com.lapissea.dfs.io.bit.FlagWriter;
import com.lapissea.dfs.io.content.ContentInputStream;
import com.lapissea.dfs.io.content.ContentOutputStream;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.io.content.ContentWriter;
import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.objects.CollectionInfo;
import com.lapissea.dfs.objects.NumberSize;
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
import java.io.UncheckedIOException;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import static com.lapissea.dfs.config.GlobalConfig.DEBUG_VALIDATION;

public abstract class DynamicSupport{
	@SuppressWarnings({"unchecked"})
	public static long calcSize(DataProvider prov, Object val){
		return switch(val){
			case null -> 0;
			case Boolean ignored -> 1;
			case Float ignored -> 4;
			case Double ignored -> 8;
			case Number integer -> 1 + NumberSize.bySizeSigned(numToLong(integer)).bytes;
			case String str -> AutoText.PIPE.calcUnknownSize(prov, new AutoText(str), WordSpace.BYTE);
			case IOInstance inst -> {
				if(inst instanceof IOInstance.Unmanaged<?> u){
					yield ChunkPointer.DYN_SIZE_DESCRIPTOR.calcUnknown(null, prov, u.getPointer(), WordSpace.BYTE);
				}else{
					yield StandardStructPipe.sizeOfUnknown(prov, inst, WordSpace.BYTE);
				}
			}
			case Enum<?> e -> BitUtils.bitsToBytes(EnumUniverse.of(e.getClass()).bitSize);
			default -> {
				var res = CollectionInfo.analyze(val);
				if(res != null){
					var infoBytes = new CollectionInfo(res).calcIOBytes();
					var len       = res.length();
					if(len == 0) yield infoBytes;
					
					var constType = res.constantType();
					
					if(res.layout() == CollectionInfo.Layout.DYNAMIC){
						long sum = infoBytes;
						for(var e : CollectionInfo.iter(res.type(), val)){
							int id;
							try{
								id = prov.getTypeDb().toID(e, false).val();
							}catch(IOException ex){
								throw new UncheckedIOException("Failed to compute type ID", ex);
							}
							sum += 1 + NumberSize.bySizeSigned(id).bytes;
							sum += calcSize(prov, e);
						}
						yield sum;
					}
					
					var nullBufferBytes = res.hasNulls()? BitUtils.bitsToBytes(len) : 0;
					
					var primitiveO = SupportedPrimitive.get(res.constantType());
					if(primitiveO.isPresent()){
						var psiz = primitiveO.get();
						yield infoBytes + nullBufferBytes + switch(psiz){
							case DOUBLE, FLOAT -> psiz.maxSize.get()*len;
							case BOOLEAN -> BitUtils.bitsToBytes(len);
							case LONG -> 1 + NumberSize.bySize(LongStream.of((long[])val).max().orElse(0)).bytes*(long)len;
							case INT -> 1 + NumberSize.bySize(IntStream.of((int[])val).max().orElse(0)).bytes*(long)len;
							case SHORT, CHAR -> 2L*len;
							case BYTE -> len;
						};
					}
					
					if(constType.isEnum()){
						//noinspection rawtypes
						var info = EnumUniverse.of((Class<Enum>)constType);
						yield infoBytes + BitUtils.bitsToBytes(info.getBitSize(res.hasNulls())*(long)len);
					}
					
					if(IOInstance.isInstance(constType)){
						var struct = Struct.ofUnknown(constType);
						if(struct instanceof Struct.Unmanaged){
							long sum = infoBytes + nullBufferBytes;
							for(var uInst : (Iterable<IOInstance.Unmanaged<?>>)CollectionInfo.iter(res.type(), val)){
								if(uInst == null) continue;
								sum += ChunkPointer.DYN_SIZE_DESCRIPTOR.calcUnknown(null, prov, uInst.getPointer(), WordSpace.BYTE);
							}
							yield sum;
						}
						
						if(res.layout() == CollectionInfo.Layout.STRUCT_OF_ARRAYS){
							throw new NotImplementedException("SOA not implemented yet");//TODO
						}
						
						StructPipe pip = StandardStructPipe.of(struct);
						
						if(pip.getSizeDescriptor().hasFixed()){
							int nnCount;
							if(res.hasNulls()){
								nnCount = 0;
								for(var inst : CollectionInfo.iter(res.type(), val)){
									if(inst == null) continue;
									nnCount++;
								}
							}else nnCount = len;
							
							yield infoBytes + nullBufferBytes + pip.getSizeDescriptor().requireFixed(WordSpace.BYTE)*nnCount;
						}
						
						long sum = infoBytes + nullBufferBytes;
						for(var inst : CollectionInfo.iter(res.type(), val)){
							if(inst == null) continue;
							sum += pip.calcUnknownSize(prov, inst, WordSpace.BYTE);
						}
						yield sum;
					}
					
					throw new ShouldNeverHappenError("Case not handled for " + res + " with " + val);
				}
				
				var wrapper = (WrapperStructs.WrapperRes<Object>)WrapperStructs.getWrapperStruct(val.getClass());
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
			case IOInstance.Unmanaged inst -> ChunkPointer.DYN_PIPE.write(provider, dest, inst.getPointer());
			case IOInstance inst -> StandardStructPipe.of(inst.getThisStruct()).write(provider, dest, inst);
			case Enum e -> FlagWriter.writeSingle(dest, EnumUniverse.of(e.getClass()), e);
			
			default -> {
				var res = CollectionInfo.analyze(val);
				if(res != null){
					
					new CollectionInfo(res).write(dest);
					
					if(res.length() == 0) break;
					
					var constType = res.constantType();
					if(res.hasNulls() && !(constType != null && constType.isEnum())){
						try(var stream = new BitOutputStream(dest)){
							for(var e : CollectionInfo.iter(res.type(), val)){
								stream.writeBoolBit(e != null);
							}
						}
					}
					
					if(res.layout() == CollectionInfo.Layout.DYNAMIC){
						var db = provider.getTypeDb();
						for(var e : CollectionInfo.iter(res.type(), val).filtered(Objects::nonNull)){
							var id = db.toID(e);
							dest.writeUnsignedInt4Dynamic(id);
							writeValue(provider, dest, e);
						}
						break;
					}
					assert constType != null;
					
					var pTypO = SupportedPrimitive.get(constType);
					if(pTypO.isPresent()){
						if(res.type() != CollectionInfo.CollectionType.ARRAY)
							throw new NotImplementedException("Non array of primitives not implemented yet");//TODO
						writePrimitiveArray(dest, val, res.length(), pTypO.get());
						break;
					}
					
					if(constType.isEnum()){
						//noinspection rawtypes
						var info = EnumUniverse.of((Class<Enum>)constType);
						try(var stream = new BitOutputStream(dest)){
							var nullable = res.hasNulls();
							for(var e : (Iterable<Enum>)CollectionInfo.iter(res.type(), val).filtered(Objects::nonNull)){
								stream.writeEnum(info, e, nullable);
							}
						}
						break;
					}
					
					if(IOInstance.isInstance(constType)){
						var iter   = CollectionInfo.iter(res.type(), val).filtered(Objects::nonNull);
						var struct = Struct.ofUnknown(constType);
						if(struct instanceof Struct.Unmanaged){
							for(var uInst : (Iterable<IOInstance.Unmanaged<?>>)iter){
								ChunkPointer.DYN_PIPE.write(provider, dest, uInst.getPointer());
							}
							break;
						}
						
						StructPipe pip = StandardStructPipe.of(struct);
						for(var inst : (Iterable<IOInstance<?>>)iter){
							pip.write(provider, dest, inst);
						}
						break;
					}
					
					throw new ShouldNeverHappenError("Case not handled for " + res + " with " + val);
				}
				
				var type    = val.getClass();
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
			case BYTE -> dest.writeInts1((byte[])array);
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
			case BYTE -> src.readInts1(len);
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
		if(!List.<Class<? extends Number>>of(Byte.class, Short.class, Integer.class, Long.class).contains(tyo))
			throw new AssertionError(tyo + " is not an integer");
	}
	
	public static Object readTyp(IOType typDef, DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
		var db  = provider.getTypeDb();
		var typ = typDef.getTypeClass(db);
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
		
		if(typ.isArray() || UtilL.instanceOf(typ, List.class)){
			var res = CollectionInfo.read(src);
			
			int len = res.length();
			
			Class<?> componentType;
			if(res.layout() != CollectionInfo.Layout.DYNAMIC){
				if(typ.isArray()){
					componentType = typ.getComponentType();
				}else{
					var arg = IOType.getArgs(typDef).getFirst();
					componentType = arg.getTypeClass(provider.getTypeDb());
				}
			}else componentType = null;
			
			BitInputStream nullBuffer;
			if(res.hasNullElements() && !(componentType != null && componentType.isEnum())){
				var bytes = BitUtils.bitsToBytes(len);
				var buff  = src.readInts1(bytes);
				nullBuffer = new BitInputStream(new ContentInputStream.BA(buff), len);
			}else nullBuffer = null;
			
			Consumer<Object> dest;
			Supplier<Object> end;
			switch(res.collectionType()){
				case null -> throw new NullPointerException();
				case NULL -> { return null; }
				case ARRAY -> {
					var ct   = typ.getComponentType();
					var arrO = Array.newInstance(ct, len);
					var arr  = ct.isPrimitive()? null : (Object[])arrO;
					dest = new Consumer<>(){
						private int i;
						@Override
						public void accept(Object o){
							if(arr == null) Array.set(arrO, i, o);
							else arr[i] = o;
							i++;
						}
					};
					end = () -> {
						try{
							if(nullBuffer != null) nullBuffer.close();
						}catch(IOException ex){ throw UtilL.uncheckedThrow(ex); }
						return arr;
					};
				}
				case ARRAY_LIST, UNMODIFIABLE_LIST -> {
					var list = new ArrayList<>(len);
					dest = list::add;
					boolean fin = res.collectionType() == CollectionInfo.CollectionType.UNMODIFIABLE_LIST;
					end = () -> {
						try{
							if(nullBuffer != null) nullBuffer.close();
						}catch(IOException ex){ throw UtilL.uncheckedThrow(ex); }
						
						if(fin){
							return List.copyOf(list);
						}
						return list;
					};
				}
			}
			
			if(res.layout() == CollectionInfo.Layout.DYNAMIC){
				for(int i = 0; i<len; i++){
					boolean hasVal = nullBuffer == null || nullBuffer.readBoolBit();
					Object  element;
					if(!hasVal) element = null;
					else{
						var id   = src.readUnsignedInt4Dynamic();
						var type = db.fromID(id);
						element = readTyp(type, provider, src, genericContext);
					}
					dest.accept(element);
				}
				return end.get();
			}
			
			assert componentType != null;
			
			var pTypO = SupportedPrimitive.get(componentType);
			if(pTypO.isPresent()){
				if(res.collectionType() != CollectionInfo.CollectionType.ARRAY)
					throw new NotImplementedException("Non array of primitives not implemented yet");//TODO
				return readPrimitiveArray(src, len, pTypO.get());
			}
			
			if(componentType.isEnum()){
				var universe = EnumUniverse.ofUnknown(componentType);
				var nullable = res.hasNullElements();
				try(var bits = new BitInputStream(src, universe.getBitSize(nullable)*(long)len)){
					for(int i = 0; i<len; i++){
						dest.accept(bits.readEnum(universe, nullable));
					}
				}
				return end.get();
			}
			
			if(IOInstance.isInstance(componentType)){
				var struct = Struct.ofUnknown(componentType);
				if(struct instanceof Struct.Unmanaged<?> u){
					for(int i = 0; i<len; i++){
						boolean                 hasVal = nullBuffer == null || nullBuffer.readBoolBit();
						IOInstance.Unmanaged<?> element;
						if(!hasVal) element = null;
						else{
							var ptr = ChunkPointer.DYN_PIPE.readNew(provider, src, null);
							var ch  = ptr.dereference(provider);
							element = u.make(provider, ch, typDef);
						}
						dest.accept(element);
					}
					return end.get();
				}
				
				var pip = StandardStructPipe.of(struct);
				for(int i = 0; i<len; i++){
					boolean       hasVal = nullBuffer == null || nullBuffer.readBoolBit();
					IOInstance<?> element;
					if(!hasVal) element = null;
					else{
						element = pip.readNew(provider, src, genericContext);
					}
					dest.accept(element);
				}
				return end.get();
			}
			
			throw new ShouldNeverHappenError("Case not handled for " + res);
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
				ChunkPointer.DYN_PIPE.skip(provider, src, null);
			}else{
				skipStruct(provider, src, genericContext, Struct.ofUnknown(typ));
			}
			return;
		}
		
		if(typ.isEnum()){
			var universe = EnumUniverse.ofUnknown(typ);
			universe.numSize(false).skip(src);
			return;
		}
		
		if(typ.isArray() || UtilL.instanceOf(typ, List.class)){
			readTyp(typDef, provider, src, genericContext);//TODO just skip, don't read
		}
		
		var wrapper = (WrapperStructs.WrapperRes<Object>)WrapperStructs.getWrapperStruct(typ);
		if(wrapper != null){
			var pip = StandardStructPipe.of(wrapper.struct());
			pip.skip(provider, src, genericContext);
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
