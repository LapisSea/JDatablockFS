package com.lapissea.dfs.type.field.fields.reflection;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.io.bit.BitInputStream;
import com.lapissea.dfs.io.bit.BitOutputStream;
import com.lapissea.dfs.io.bit.BitUtils;
import com.lapissea.dfs.io.bit.EnumUniverse;
import com.lapissea.dfs.io.bit.FlagReader;
import com.lapissea.dfs.io.bit.FlagWriter;
import com.lapissea.dfs.io.content.BBView;
import com.lapissea.dfs.io.content.ContentInputStream;
import com.lapissea.dfs.io.content.ContentOutputBuilder;
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
import com.lapissea.dfs.utils.IterablePP;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.TextUtil;
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
				var primitiveO = SupportedPrimitive.get(val.getClass());
				if(primitiveO.isPresent()){
					var psiz = primitiveO.get();
					yield switch(psiz){
						case LONG -> 1 + NumberSize.bySizeSigned((long)val).bytes;
						case INT -> 1 + NumberSize.bySizeSigned((int)val).bytes;
						case BOOLEAN, BYTE, DOUBLE, FLOAT, SHORT, CHAR -> psiz.maxSize.get();
					};
				}
				
				var res = CollectionInfo.analyze(val);
				if(res != null) yield calcCollectionSize(prov, val, res);
				
				var wrapper = (WrapperStructs.WrapperRes<Object>)WrapperStructs.getWrapperStruct(val.getClass());
				if(wrapper != null){
					var obj = wrapper.constructor().apply(val);
					yield StandardStructPipe.sizeOfUnknown(prov, obj, WordSpace.BYTE);
				}
				
				throw new NotImplementedException(val.getClass() + "");
			}
		};
	}
	
	private static long calcCollectionSize(DataProvider prov, Object val, CollectionInfo.AnalysisResult res){
		var infoBytes = new CollectionInfo(res).calcIOBytes();
		var len       = res.length();
		if(len == 0 || res.layout() == CollectionInfo.Layout.NO_VALUES) return infoBytes;
		
		var constType = res.constantType();
		
		var nullBufferBytes = res.hasNulls()? BitUtils.bitsToBytes(len) : 0;
		
		if(res.layout() == CollectionInfo.Layout.DYNAMIC){
			long sum = infoBytes + nullBufferBytes;
			for(var el : CollectionInfo.iter(res.type(), val).filtered(Objects::nonNull)){
				int id;
				try{
					id = prov.getTypeDb().toID(el, false).val();
				}catch(IOException ex){
					throw new UncheckedIOException("Failed to compute type ID", ex);
				}
				sum += 1 + NumberSize.bySizeSigned(id).bytes;
				sum += calcSize(prov, el);
			}
			return sum;
		}
		
		var primitiveO = SupportedPrimitive.get(res.constantType());
		if(primitiveO.isPresent()){
			var psiz = primitiveO.get();
			int actualElements;
			if(!res.hasNulls()) actualElements = len;
			else{
				actualElements = CollectionInfo.iter(res.type(), val).filtered(Objects::nonNull).count();
			}
			return infoBytes + nullBufferBytes + switch(psiz){
				case DOUBLE, FLOAT -> psiz.maxSize.get()*actualElements;
				case BOOLEAN -> BitUtils.bitsToBytes(actualElements);
				case LONG -> {
					int bytesPer;
					if(val instanceof long[] arr){
						bytesPer = 0;
						for(var i : arr){
							bytesPer = Math.max(bytesPer, NumberSize.bySizeSigned(i).bytes);
						}
					}else bytesPer = CollectionInfo.iter(res.type(), val).filtered(Objects::nonNull).map(Long.class::cast)
					                               .map(NumberSize::bySizeSigned).reduce(NumberSize::max).orElse(NumberSize.VOID).bytes;
					yield 1 + bytesPer*(long)actualElements;
				}
				case INT -> {
					int bytesPer;
					if(val instanceof int[] arr){
						bytesPer = 0;
						for(var i : arr){
							bytesPer = Math.max(bytesPer, NumberSize.bySizeSigned(i).bytes);
						}
					}else bytesPer = CollectionInfo.iter(res.type(), val).filtered(Objects::nonNull).map(Integer.class::cast)
					                               .map(NumberSize::bySizeSigned).reduce(NumberSize::max).orElse(NumberSize.VOID).bytes;
					yield 1 + bytesPer*(long)actualElements;
				}
				case SHORT, CHAR -> 2L*actualElements;
				case BYTE -> actualElements;
			};
		}
		
		if(constType.isEnum()){
			//noinspection rawtypes
			var info = EnumUniverse.of((Class<Enum>)constType);
			return infoBytes + BitUtils.bitsToBytes(info.getBitSize(res.hasNulls())*(long)len);
		}
		
		if(IOInstance.isInstance(constType)){
			var struct = Struct.ofUnknown(constType);
			if(struct instanceof Struct.Unmanaged){
				long sum = infoBytes + nullBufferBytes;
				for(var uInst : (Iterable<IOInstance.Unmanaged<?>>)CollectionInfo.iter(res.type(), val)){
					if(uInst == null) continue;
					sum += ChunkPointer.DYN_SIZE_DESCRIPTOR.calcUnknown(null, prov, uInst.getPointer(), WordSpace.BYTE);
				}
				return sum;
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
				
				return infoBytes + nullBufferBytes + pip.getSizeDescriptor().requireFixed(WordSpace.BYTE)*nnCount;
			}
			
			long sum = infoBytes + nullBufferBytes;
			for(var inst : CollectionInfo.iter(res.type(), val)){
				if(inst == null) continue;
				sum += pip.calcUnknownSize(prov, inst, WordSpace.BYTE);
			}
			return sum;
		}
		
		//noinspection unchecked
		var wrapperType = WrapperStructs.getWrapperStruct((Class<Object>)constType);
		if(wrapperType != null){
			var  pip  = StandardStructPipe.of(wrapperType.struct());
			var  ctor = wrapperType.constructor();
			long sum  = infoBytes + nullBufferBytes;
			for(var inst : CollectionInfo.iter(res.type(), val)){
				if(inst == null) continue;
				sum += pip.calcUnknownSize(prov, ctor.apply(inst), WordSpace.BYTE);
			}
			return sum;
		}
		
		nestedCollection:
		{
			long sum = infoBytes + nullBufferBytes;
			for(var e : CollectionInfo.iter(res.type(), val).filtered(Objects::nonNull)){
				var eRes = CollectionInfo.analyze(e);
				if(eRes == null) break nestedCollection;
				sum += calcCollectionSize(prov, e, eRes);
			}
			return sum;
		}
		
		throw new ShouldNeverHappenError("Case not handled for " + res + " with " + TextUtil.toString(val));
	}
	
	@SuppressWarnings("unchecked")
	public static void writeValue(DataProvider provider, ContentWriter dest, Object val) throws IOException{
		switch(val){
			case null -> { }
			case String str -> AutoText.STR_PIPE.write(provider, dest, str);
			case IOInstance.Unmanaged inst -> ChunkPointer.DYN_PIPE.write(provider, dest, inst.getPointer());
			case IOInstance inst -> StandardStructPipe.of(inst.getThisStruct()).write(provider, dest, inst);
			case Enum e -> FlagWriter.writeSingle(dest, EnumUniverse.of(e.getClass()), e);
			case Boolean v -> dest.writeBoolean(v);
			case Character v -> dest.writeChar2(v);
			case Float v -> NumberSize.INT.writeFloating(dest, v);
			case Double v -> NumberSize.LONG.writeFloating(dest, v);
			case Byte v -> dest.writeInt1(v);
			case Short v -> dest.writeInt2(v);
			case Number integer -> {
				long value = numToLong(integer);
				var  num   = NumberSize.bySizeSigned(value);
				FlagWriter.writeSingle(dest, NumberSize.FLAG_INFO, num);
				num.writeSigned(dest, value);
			}
			
			default -> {
				var res = CollectionInfo.analyze(val);
				if(res != null){
					writeCollection(provider, dest, val, res);
					break;
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
	
	private static void writeCollection(DataProvider provider, ContentWriter dest, Object val, CollectionInfo.AnalysisResult res) throws IOException{
		new CollectionInfo(res).write(dest);
		if(res.length() == 0 || res.layout() == CollectionInfo.Layout.NO_VALUES) return;
		
		var constType       = res.constantType();
		int nonNullElements = res.length();
		if(res.hasNulls() && !(constType != null && constType.isEnum())){
			var buf = new ContentOutputBuilder();
			try(var stream = new BitOutputStream(buf)){
				nonNullElements = 0;
				for(var e : CollectionInfo.iter(res.type(), val)){
					var b = e != null;
					nonNullElements += b? 1 : 0;
					stream.writeBoolBit(b);
				}
			}
			buf.writeTo(dest);
		}
		
		if(res.layout() == CollectionInfo.Layout.DYNAMIC){
			var db = provider.getTypeDb();
			for(var el : CollectionInfo.iter(res.type(), val).filtered(Objects::nonNull)){
				var id = db.toID(el);
				dest.writeUnsignedInt4Dynamic(id);
				writeValue(provider, dest, el);
			}
			return;
		}
		assert constType != null;
		
		var pTypO = SupportedPrimitive.get(constType);
		if(pTypO.isPresent()){
			if(res.type() != CollectionInfo.CollectionType.ARRAY){
				var iter = CollectionInfo.iter(res.type(), val).filtered(Objects::nonNull);
				writePrimitiveCollection(dest, iter, nonNullElements, pTypO.get());
				return;
			}
			writePrimitiveArray(dest, val, res.length(), pTypO.get());
			return;
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
			return;
		}
		
		if(IOInstance.isInstance(constType)){
			var iter   = CollectionInfo.iter(res.type(), val).filtered(Objects::nonNull);
			var struct = Struct.ofUnknown(constType);
			if(struct instanceof Struct.Unmanaged){
				for(var uInst : (Iterable<IOInstance.Unmanaged<?>>)iter){
					ChunkPointer.DYN_PIPE.write(provider, dest, uInst.getPointer());
				}
				return;
			}
			
			StructPipe pip = StandardStructPipe.of(struct);
			for(var inst : (Iterable<IOInstance<?>>)iter){
				pip.write(provider, dest, inst);
			}
			return;
		}
		
		//noinspection unchecked
		var wrapperType = WrapperStructs.getWrapperStruct((Class<Object>)constType);
		if(wrapperType != null){
			var pip  = StandardStructPipe.of(wrapperType.struct());
			var ctor = wrapperType.constructor();
			for(var inst : CollectionInfo.iter(res.type(), val)){
				if(inst == null) continue;
				pip.write(provider, dest, ctor.apply(inst));
			}
			return;
		}
		
		for(var e : CollectionInfo.iter(res.type(), val).filtered(Objects::nonNull)){
			var eRes = CollectionInfo.analyze(e);
			if(eRes == null) throw new ShouldNeverHappenError("Case not handled for " + res + " with " + val);
			writeCollection(provider, dest, e, eRes);
		}
	}
	
	private static void writePrimitiveArray(ContentWriter dest, Object array, int len, SupportedPrimitive pTyp) throws IOException{
		switch(pTyp){
			case null -> throw new NullPointerException();
			case DOUBLE -> dest.writeFloats8((double[])array);
			case CHAR -> dest.writeChars2((char[])array);
			case FLOAT -> dest.writeFloats4((float[])array);
			case BOOLEAN -> {
				try(var bitOut = new BitOutputStream(dest)){
					bitOut.writeBits((boolean[])array);
				}
			}
			case LONG -> {
				var siz = NumberSize.bySizeSigned(LongStream.of((long[])array).max().orElse(0));
				FlagWriter.writeSingle(dest, NumberSize.FLAG_INFO, siz);
				
				byte[] bb = new byte[siz.bytes*len];
				try(var io = new ContentOutputStream.BA(bb)){
					for(long l : (long[])array){
						siz.writeSigned(io, l);
					}
				}
				dest.write(bb);
			}
			case INT -> {
				var siz = NumberSize.bySizeSigned(IntStream.of((int[])array).max().orElse(0));
				FlagWriter.writeSingle(dest, NumberSize.FLAG_INFO, siz);
				
				byte[] bb = new byte[siz.bytes*len];
				try(var io = new ContentOutputStream.BA(bb)){
					for(var l : (int[])array){
						siz.writeIntSigned(io, l);
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
	private static void writePrimitiveCollection(ContentWriter dest, IterablePP<?> array, int len, SupportedPrimitive pTyp) throws IOException{
		switch(pTyp){
			case null -> throw new NullPointerException();
			case DOUBLE -> {
				int    numSize = 8;
				byte[] bb      = new byte[len*numSize];
				var    i       = 0;
				for(var o : array){
					BBView.writeFloat8(bb, i*numSize, (double)o);
					i++;
				}
				assert len == i : len + " != " + i;
				dest.write(bb, 0, bb.length);
			}
			case CHAR -> {
				int    numSize = 2;
				byte[] bb      = new byte[len*numSize];
				var    i       = 0;
				for(var o : array){
					BBView.writeChar2(bb, i*numSize, (char)o);
					i++;
				}
				assert len == i : len + " != " + i;
				dest.write(bb, 0, bb.length);
			}
			case FLOAT -> {
				int    numSize = 4;
				byte[] bb      = new byte[len*numSize];
				var    i       = 0;
				for(var o : array){
					BBView.writeFloat4(bb, i*numSize, (float)o);
					i++;
				}
				assert len == i : len + " != " + i;
				dest.write(bb, 0, bb.length);
			}
			case BOOLEAN -> {
				try(var bitOut = new BitOutputStream(dest)){
					for(var o : array){
						bitOut.writeBoolBit((boolean)o);
					}
				}
			}
			case LONG -> {
				var nums = array.map(Long.class::cast);
				var siz  = nums.map(NumberSize::bySizeSigned).reduce(NumberSize::max).orElse(NumberSize.VOID);
				FlagWriter.writeSingle(dest, NumberSize.FLAG_INFO, siz);
				
				byte[] bb = new byte[siz.bytes*len];
				try(var io = new ContentOutputStream.BA(bb)){
					for(long l : nums){
						siz.writeSigned(io, l);
					}
				}
				dest.write(bb);
			}
			case INT -> {
				var nums = array.map(Integer.class::cast);
				var siz  = nums.map(NumberSize::bySizeSigned).reduce(NumberSize::max).orElse(NumberSize.VOID);
				FlagWriter.writeSingle(dest, NumberSize.FLAG_INFO, siz);
				
				byte[] bb = new byte[siz.bytes*len];
				try(var io = new ContentOutputStream.BA(bb)){
					for(var l : nums){
						siz.writeIntSigned(io, l);
					}
				}
				dest.write(bb);
			}
			case SHORT -> {
				byte[] bb = new byte[len*2];
				try(var io = new ContentOutputStream.BA(bb)){
					for(var l : array){
						io.writeInt2(((short)l)&0xFFFF);
					}
				}
				dest.write(bb);
			}
			case BYTE -> {
				int    siz = array.count();
				byte[] bb  = new byte[siz];
				int    i   = 0;
				for(var o : array){
					bb[i++] = (byte)o;
				}
				assert len == i : len + " != " + i;
				dest.writeInts1(bb);
			}
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
					arr[i] = siz.readInt(src);
				}
				yield arr;
			}
			case SHORT -> src.readInts2(len);
			case CHAR -> src.readChars2(len);
			case BYTE -> src.readInts1(len);
		};
	}
	private static void readPrimitiveCollection(ContentReader src, int len, SupportedPrimitive pTyp, Consumer<Object> dest) throws IOException{
		switch(pTyp){
			case null -> throw new NullPointerException();
			case DOUBLE -> {
				for(double v : src.readFloats8(len)){
					dest.accept(v);
				}
			}
			case FLOAT -> {
				for(float v : src.readFloats4(len)){
					dest.accept(v);
				}
			}
			case BOOLEAN -> {
				try(var bitIn = new BitInputStream(src, len)){
					for(boolean b : bitIn.readBits(new boolean[len])){
						dest.accept(b);
					}
				}
			}
			case LONG -> {
				var siz = FlagReader.readSingle(src, NumberSize.FLAG_INFO);
				for(int i = 0; i<len; i++){
					dest.accept(siz.read(src));
				}
			}
			case INT -> {
				var siz = FlagReader.readSingle(src, NumberSize.FLAG_INFO);
				var arr = new int[len];
				for(int i = 0; i<arr.length; i++){
					dest.accept(siz.readInt(src));
				}
			}
			case SHORT -> {
				for(short i : src.readInts2(len)){
					dest.accept(i);
				}
			}
			case CHAR -> {
				for(char c : src.readChars2(len)){
					dest.accept(c);
				}
			}
			case BYTE -> {
				for(byte b : src.readInts1(len)){
					dest.accept(b);
				}
			}
		}
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
		if(typ == Character.class) return src.readChar2();
		if(typ == Float.class) return (float)NumberSize.INT.readFloating(src);
		if(typ == Double.class) return NumberSize.LONG.readFloating(src);
		if(typ == Byte.class) return src.readInt1();
		if(typ == Short.class) return src.readInt2();
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
		if(typ == String.class) return AutoText.STR_PIPE.readNew(provider, src, null);
		
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
		
		if(CollectionInfo.isTypeCollection(typ)){
			return readCollection(typDef, provider, src, genericContext);
		}
		
		var wrapper = (WrapperStructs.WrapperRes<Object>)WrapperStructs.getWrapperStruct(typ);
		if(wrapper != null){
			var pip = StandardStructPipe.of(wrapper.struct());
			var obj = pip.readNew(provider, src, genericContext);
			return obj.get();
		}
		
		throw new NotImplementedException(typ + "");
	}
	private static Object readCollection(IOType typDef, DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
		var typ = typDef.getTypeClass(provider.getTypeDb());
		var res = CollectionInfo.read(src);
		int len = res.length();
		
		Class<?> componentType;
		IOType   componentIOType;
		if(res.layout() != CollectionInfo.Layout.DYNAMIC){
			if(typ.isArray()){
				componentType = typ.getComponentType();
				if(typDef instanceof IOType.RawAndArg raaaa){
					componentIOType = raaaa.withRaw(typDef.getTypeClass(provider.getTypeDb()).componentType());
				}else{
					throw new NotImplementedException(typDef + "could not be array unwrapped");
				}
			}else{
				var args = IOType.getArgs(typDef);
				var arg  = args.getFirst();
				componentIOType = arg;
				componentType = arg.getTypeClass(provider.getTypeDb());
			}
		}else{
			componentType = null;
			componentIOType = null;
		}
		
		BitInputStream nullBuffer;
		byte[]         nullBufferBytes;
		if(res.hasNullElements() && res.layout() != CollectionInfo.Layout.NO_VALUES && !(componentType != null && componentType.isEnum())){
			var bytes = BitUtils.bitsToBytes(len);
			nullBufferBytes = src.readInts1(bytes);
			nullBuffer = new BitInputStream(new ContentInputStream.BA(nullBufferBytes), len);
		}else{
			nullBuffer = null;
			nullBufferBytes = null;
		}
		
		Consumer<Object> dest;
		Supplier<Object> end;
		switch(res.collectionType()){
			case null -> throw new NullPointerException();
			case NULL -> { return null; }
			case ARRAY -> {
				var ct  = typ.getComponentType();
				var arr = ct.isPrimitive()? null : (Object[])Array.newInstance(ct, len);
				dest = new Consumer<>(){
					private int i;
					@Override
					public void accept(Object o){
						assert arr != null;
						arr[i++] = o;
					}
				};
				end = () -> {
					try{
						if(nullBuffer != null) nullBuffer.close();
					}catch(IOException ex){ throw UtilL.uncheckedThrow(ex); }
					if(arr == null){
						assert len == 0;
						return Array.newInstance(ct, len);
					}
					return arr;
				};
			}
			case ARRAY_LIST, UNMODIFIABLE_LIST -> {
				var list = new ArrayList<>(len);
				dest = list::add;
				boolean fin = res.collectionType() == CollectionInfo.CollectionType.UNMODIFIABLE_LIST;
				end = () -> {
					try{
						if(nullBuffer != null){
							nullBuffer.close();
						}
					}catch(IOException ex){ throw UtilL.uncheckedThrow(ex); }
					
					if(fin){
						return List.copyOf(list);
					}
					return list;
				};
			}
		}
		
		if(res.length() == 0) return end.get();
		
		if(res.layout() == CollectionInfo.Layout.NO_VALUES){
			for(int i = 0; i<res.length(); i++){
				dest.accept(null);
			}
			return end.get();
		}
		
		if(res.layout() == CollectionInfo.Layout.DYNAMIC){
			var db = provider.getTypeDb();
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
			if(res.collectionType() != CollectionInfo.CollectionType.ARRAY){
				int eCount;
				if(nullBufferBytes == null) eCount = len;
				else{
					eCount = 0;
					var lm1 = nullBufferBytes.length - 1;
					for(int i = 0; i<lm1; i++){
						eCount += Integer.bitCount(Byte.toUnsignedInt(nullBufferBytes[i]));
					}
					var endBits = len - lm1*8;
					try(var f = new FlagReader(nullBufferBytes[lm1], endBits)){
						for(int i = 0; i<endBits; i++){
							if(f.readBoolBit()) eCount++;
						}
					}
				}
				if(nullBufferBytes != null){
					var tmp = new ArrayList<>(eCount);
					readPrimitiveCollection(src, eCount, pTypO.get(), tmp::add);
					for(int i = 0, j = 0; i<len; i++){
						var hasVal = nullBuffer.readBoolBit();
						dest.accept(hasVal? tmp.get(j++) : null);
					}
				}else{
					readPrimitiveCollection(src, eCount, pTypO.get(), dest);
				}
				return end.get();
			}
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
						element = u.make(provider, ch, componentIOType);
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
		
		//noinspection unchecked
		var wrapperType = WrapperStructs.getWrapperStruct((Class<Object>)componentType);
		if(wrapperType != null){
			var pip = StandardStructPipe.of(wrapperType.struct());
			for(int i = 0; i<len; i++){
				boolean hasVal = nullBuffer == null || nullBuffer.readBoolBit();
				Object  element;
				if(!hasVal) element = null;
				else{
					element = pip.readNew(provider, src, genericContext).get();
				}
				dest.accept(element);
			}
			return end.get();
		}
		
		if(CollectionInfo.isTypeCollection(componentType)){
			for(int i = 0; i<len; i++){
				boolean hasVal = nullBuffer == null || nullBuffer.readBoolBit();
				Object  element;
				if(!hasVal) element = null;
				else{
					element = readCollection(componentIOType, provider, src, genericContext);
				}
				dest.accept(element);
			}
			return end.get();
		}
		
		throw new ShouldNeverHappenError("Case not handled for " + res + " with " + typ.getTypeName());
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
