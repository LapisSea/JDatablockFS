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
import com.lapissea.dfs.io.content.ContentOutputStream;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.io.content.ContentWriter;
import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.objects.CollectionInfo;
import com.lapissea.dfs.objects.CollectionInfoAnalysis;
import com.lapissea.dfs.objects.NumberSize;
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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.stream.IntStream;
import java.util.stream.LongStream;


/**
 * Collection format:
 * <p>
 * {Collection header: length, type, element layout, has nulls...}
 * [null bits] //Optional
 * [values] //Only non-null values are written.
 */


public abstract class DynamicCollectionSupport{
	
	private interface Generator{
		void add(Object element);
		Object export() throws IOException;
	}
	
	private static int calcNullBufferSize(CollectionInfo res){
		return res.hasNulls()? BitUtils.bitsToBytes(res.length()) : 0;
	}
	
	
	private static long primitiveSiz(Object val, CollectionInfo info, SupportedPrimitive pTyp){
		int actualElements;
		if(!info.hasNulls()) actualElements = info.length();
		else{
			actualElements = info.iter(val).filtered(Objects::nonNull).count();
		}
		
		return calcNullBufferSize(info) + switch(pTyp){
			case DOUBLE, FLOAT -> pTyp.maxSize.get()*actualElements;
			case BOOLEAN -> BitUtils.bitsToBytes(actualElements);
			case LONG -> {
				int bytesPer;
				if(val instanceof long[] arr){
					bytesPer = 0;
					for(var i : arr){
						bytesPer = Math.max(bytesPer, NumberSize.bySizeSigned(i).bytes);
					}
				}else bytesPer = info.iter(val).filtered(Objects::nonNull).map(Long.class::cast)
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
				}else bytesPer = info.iter(val).filtered(Objects::nonNull).map(Integer.class::cast)
				                     .map(NumberSize::bySizeSigned).reduce(NumberSize::max).orElse(NumberSize.VOID).bytes;
				yield 1 + bytesPer*(long)actualElements;
			}
			case SHORT, CHAR -> 2L*actualElements;
			case BYTE -> actualElements;
		};
	}
	private static Object primitiveRead(ContentReader src, CollectionInfo info, SupportedPrimitive pTyp, byte[] nullBufferBytes) throws IOException{
		
		if(info instanceof CollectionInfo.PrimitiveArrayInfo primitive){
			var pTypO = SupportedPrimitive.getStrict(primitive.constantType()).orElseThrow(() -> {
				return new IllegalStateException("primitive array must have a primitive type");
			});
			return readPrimitiveArray(src, primitive.length(), pTypO);
		}
		
		int len = info.length();
		
		if(!info.hasNulls()){
			var gen = createGenerator(info, null);
			readPrimitiveCollection(src, len, pTyp, gen);
			return gen.export();
		}
		
		var nullBuffer = new BitInputStream(new ContentInputStream.BA(nullBufferBytes), len);
		
		var generator = createGenerator(info, nullBuffer);
		
		var eCount = 0;
		var lm1    = nullBufferBytes.length - 1;
		for(int i = 0; i<lm1; i++){
			eCount += Integer.bitCount(Byte.toUnsignedInt(nullBufferBytes[i]));
		}
		var endBits = len - lm1*8;
		try(var f = new FlagReader(nullBufferBytes[lm1], endBits)){
			for(int i = 0; i<endBits; i++){
				if(f.readBoolBit()) eCount++;
			}
		}
		
		var tmp = new ArrayList<>(eCount);//TODO: remove tmp arraylist
		readPrimitiveCollection(src, eCount, pTyp, new Generator(){
			@Override
			public void add(Object element){ tmp.add(element); }
			@Override
			public Object export(){ return null; }
		});
		for(int i = 0, j = 0; i<len; i++){
			var hasVal = nullBuffer.readBoolBit();
			generator.add(hasVal? tmp.get(j++) : null);
		}
		return generator.export();
	}
	private static void primitiveWrite(ContentWriter dest, Object val, CollectionInfo info, SupportedPrimitive pTyp) throws IOException{
		if(info instanceof CollectionInfo.PrimitiveArrayInfo){
			writePrimitiveArray(dest, val, info.length(), pTyp);
		}else{
			if(info.hasNulls()){
				var iter = info.iter(val).filtered(Objects::nonNull);
				writePrimitiveCollection(dest, iter, iter.count(), pTyp);
			}else{
				writePrimitiveCollection(dest, info.iter(val), info.length(), pTyp);
			}
		}
	}
	
	private static long dynamicSiz(DataProvider prov, Object val, CollectionInfo res){
		long sum = calcNullBufferSize(res);
		for(var el : res.iter(val).filtered(Objects::nonNull)){
			int id;
			try{
				id = prov.getTypeDb().objToID(el, false).val();
			}catch(IOException ex){
				throw new UncheckedIOException("Failed to compute type ID", ex);
			}
			sum += 1 + NumberSize.bySizeSigned(id).bytes;
			sum += DynamicSupport.calcSize(prov, el);
		}
		return sum;
	}
	private static Object dynamicRead(DataProvider provider, ContentReader src, GenericContext genericContext, CollectionInfo info, byte[] nullBufferBytes) throws IOException{
		var len        = info.length();
		var nullBuffer = nullBufferBytes == null? null : new BitInputStream(new ContentInputStream.BA(nullBufferBytes), len);
		var generator  = createGenerator(info, nullBuffer);
		
		var db = provider.getTypeDb();
		for(int i = 0; i<len; i++){
			boolean hasVal = nullBuffer == null || nullBuffer.readBoolBit();
			Object  element;
			if(!hasVal) element = null;
			else{
				var id   = src.readUnsignedInt4Dynamic();
				var type = db.fromID(id);
				element = DynamicSupport.readTyp(type, provider, src, genericContext);
			}
			generator.add(element);
		}
		return generator.export();
	}
	private static void dynamicWrite(DataProvider provider, ContentWriter dest, Object val, CollectionInfo info) throws IOException{
		var db = provider.getTypeDb();
		for(var el : info.iter(val).filtered(Objects::nonNull)){
			var id = db.objToID(el);
			dest.writeUnsignedInt4Dynamic(id);
			DynamicSupport.writeValue(provider, dest, el);
		}
	}
	
	private static long enumSiz(CollectionInfo res){
		//noinspection unchecked,rawtypes
		var info = EnumUniverse.of((Class<Enum>)res.constantType());
		return BitUtils.bitsToBytes(info.getBitSize(res.hasNulls())*(long)res.length());
	}
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static Object enumRead(ContentReader src, CollectionInfo res) throws IOException{
		var universe  = EnumUniverse.of((Class<Enum>)res.constantType());
		var nullable  = res.hasNulls();
		var len       = res.length();
		var generator = createGenerator(res, null);
		try(var bits = new BitInputStream(src, universe.getBitSize(nullable)*(long)len)){
			for(int i = 0; i<len; i++){
				generator.add(bits.readEnum(universe, nullable));
			}
		}
		return generator.export();
	}
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void enumWrite(ContentWriter dest, Object val, CollectionInfo res) throws IOException{
		var info = EnumUniverse.of((Class<Enum>)res.constantType());
		try(var stream = new BitOutputStream(dest)){
			var nullable = res.hasNulls();
			for(var e : (Iterable<Enum>)res.iter(val)){
				stream.writeEnum(info, e, nullable);
			}
		}
	}
	
	private static long instanceSiz(DataProvider prov, Object val, CollectionInfo res){
		var struct = Struct.ofUnknown(res.constantType());
		
		var nullBufferBytes = calcNullBufferSize(res);
		
		if(struct instanceof Struct.Unmanaged){
			long sum = nullBufferBytes;
			for(var uInst : (IterablePP<IOInstance.Unmanaged<?>>)res.iter(val)){
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
				for(var inst : res.iter(val)){
					if(inst == null) continue;
					nnCount++;
				}
			}else nnCount = res.length();
			
			return nullBufferBytes + pip.getSizeDescriptor().requireFixed(WordSpace.BYTE)*nnCount;
		}
		
		long sum = nullBufferBytes;
		for(var inst : res.iter(val)){
			if(inst == null) continue;
			sum += pip.calcUnknownSize(prov, inst, WordSpace.BYTE);
		}
		return sum;
	}
	private static Object instanceRead(DataProvider provider, ContentReader src, CollectionInfo res, byte[] nullBufferBytes, GenericContext genericContext) throws IOException{
		var componentIOType = IOType.of(res.constantType());
		
		var len        = res.length();
		var nullBuffer = nullBufferBytes == null? null : new BitInputStream(new ContentInputStream.BA(nullBufferBytes), len);
		var generator  = createGenerator(res, nullBuffer);
		var struct     = Struct.ofUnknown(res.constantType());
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
				generator.add(element);
			}
			return generator.export();
		}
		
		var pip = StandardStructPipe.of(struct);
		for(int i = 0; i<len; i++){
			boolean       hasVal = nullBuffer == null || nullBuffer.readBoolBit();
			IOInstance<?> element;
			if(!hasVal) element = null;
			else{
				element = pip.readNew(provider, src, genericContext);
			}
			generator.add(element);
		}
		return generator.export();
	}
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void instanceWrite(DataProvider provider, ContentWriter dest, Object val, CollectionInfo res) throws IOException{
		var iter   = res.iter(val).filtered(Objects::nonNull);
		var struct = Struct.ofUnknown(res.constantType());
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
	}
	
	private static long wrapperSiz(DataProvider provider, Object val, CollectionInfo res, WrapperStructs.WrapperRes<Object> wrapperType){
		var  pip  = StandardStructPipe.of(wrapperType.struct());
		var  ctor = wrapperType.constructor();
		long sum  = calcNullBufferSize(res);
		for(var inst : res.iter(val)){
			if(inst == null) continue;
			sum += pip.calcUnknownSize(provider, ctor.apply(inst), WordSpace.BYTE);
		}
		return sum;
	}
	private static Object wrapperRead(DataProvider provider, ContentReader src, CollectionInfo res, byte[] nullBufferBytes, WrapperStructs.WrapperRes<Object> wrapperType) throws IOException{
		var len        = res.length();
		var nullBuffer = nullBufferBytes == null? null : new BitInputStream(new ContentInputStream.BA(nullBufferBytes), len);
		var generator  = createGenerator(res, nullBuffer);
		var pip        = StandardStructPipe.of(wrapperType.struct());
		for(int i = 0; i<len; i++){
			boolean hasVal = nullBuffer == null || nullBuffer.readBoolBit();
			Object  element;
			if(!hasVal) element = null;
			else{
				element = pip.readNew(provider, src, null).get();
			}
			generator.add(element);
		}
		return generator.export();
	}
	private static void wrapperWrite(DataProvider provider, ContentWriter dest, Object val, CollectionInfo res, WrapperStructs.WrapperRes<Object> wrapperType) throws IOException{
		var pip  = StandardStructPipe.of(wrapperType.struct());
		var ctor = wrapperType.constructor();
		for(var inst : res.iter(val).filtered(Objects::nonNull)){
			pip.write(provider, dest, ctor.apply(inst));
		}
	}
	
	private static OptionalLong collectionSiz(DataProvider provider, Object val, CollectionInfo res){
		long sum = calcNullBufferSize(res);
		for(var e : res.iter(val).filtered(Objects::nonNull)){
			var eRes = CollectionInfoAnalysis.analyze(e);
			if(eRes == null) return OptionalLong.empty();
			sum += calcCollectionSize(provider, e, eRes);
		}
		return OptionalLong.of(sum);
	}
	private static Object collectionRead(DataProvider provider, ContentReader src, CollectionInfo res, byte[] nullBufferBytes, GenericContext genericContext) throws IOException{
		var componentIOType = IOType.of(res.constantType());
		
		var len        = res.length();
		var nullBuffer = nullBufferBytes == null? null : new BitInputStream(new ContentInputStream.BA(nullBufferBytes), len);
		var generator  = createGenerator(res, nullBuffer);
		for(int i = 0; i<len; i++){
			boolean hasVal = nullBuffer == null || nullBuffer.readBoolBit();
			Object  element;
			if(!hasVal) element = null;
			else{
				element = readCollection(componentIOType, provider, src, genericContext);
			}
			generator.add(element);
		}
		return generator.export();
	}
	private static void collectionWrite(DataProvider provider, ContentWriter dest, Object val, CollectionInfo res) throws IOException{
		for(var e : res.iter(val).filtered(Objects::nonNull)){
			var eRes = CollectionInfoAnalysis.analyze(e);
			Objects.requireNonNull(eRes);
			writeCollection(provider, dest, e, eRes);
		}
	}
	
	static long calcCollectionSize(DataProvider prov, Object val, CollectionInfo res){
		var infoBytes = res.calcIOBytes(prov);
		var len       = res.length();
		if(len == 0) return infoBytes;
		
		var constType = res.constantType();
		
		
		switch(res.layout()){
			case JUST_NULLS -> {
				return infoBytes;
			}
			case DYNAMIC -> {
				return infoBytes + dynamicSiz(prov, val, res);
			}
		}
		
		var primitiveType = SupportedPrimitive.get(constType);
		if(primitiveType.isPresent()){
			return infoBytes + primitiveSiz(val, res, primitiveType.get());
		}
		
		if(constType.isEnum()){
			return infoBytes + enumSiz(res);
		}
		
		if(IOInstance.isInstance(constType)){
			return infoBytes + instanceSiz(prov, val, res);
		}
		
		//noinspection unchecked
		var wrapperType = WrapperStructs.getWrapperStruct((Class<Object>)constType);
		if(wrapperType != null){
			return infoBytes + wrapperSiz(prov, val, res, wrapperType);
		}
		
		var siz = collectionSiz(prov, val, res);
		if(siz.isPresent()){
			return infoBytes + siz.getAsLong();
		}
		
		throw new ShouldNeverHappenError("Case not handled for " + res + " with " + TextUtil.toString(val));
	}
	
	static void writeCollection(DataProvider provider, ContentWriter dest, Object val, CollectionInfo res) throws IOException{
		res.write(provider, dest);
		if(res.length() == 0 || res.layout() == CollectionInfo.Layout.JUST_NULLS) return;
		
		var constType = res.constantType();
		if(res.hasNulls() && !(constType != null && constType.isEnum())){
			try(var stream = new BitOutputStream(dest)){
				for(var e : res.iter(val)){
					var b = e != null;
					stream.writeBoolBit(b);
				}
			}
		}
		
		if(res.layout() == CollectionInfo.Layout.DYNAMIC){
			dynamicWrite(provider, dest, val, res);
			return;
		}
		
		assert constType != null;
		
		var pTypO = SupportedPrimitive.get(constType);
		if(pTypO.isPresent()){
			var pTyp = pTypO.get();
			primitiveWrite(dest, val, res, pTyp);
			return;
		}
		
		if(constType.isEnum()){
			enumWrite(dest, val, res);
			return;
		}
		
		if(IOInstance.isInstance(constType)){
			instanceWrite(provider, dest, val, res);
			return;
		}
		
		//noinspection unchecked
		var wrapperType = WrapperStructs.getWrapperStruct((Class<Object>)constType);
		if(wrapperType != null){
			wrapperWrite(provider, dest, val, res, wrapperType);
			return;
		}
		
		collectionWrite(provider, dest, val, res);
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
	
	
	static Object readCollection(IOType typDef, DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
		var typ = typDef.getTypeClass(provider.getTypeDb());
		if(!CollectionInfoAnalysis.isTypeCollection(typ)){
			throw new IllegalArgumentException(typ + " is not a collection like");
		}
		
		
		var res = CollectionInfo.read(provider, src);
		int len = res.length();
		
		var layout = res.layout();
		
		Class<?> componentType;
		IOType   componentIOType;
		if(layout != CollectionInfo.Layout.DYNAMIC && layout != CollectionInfo.Layout.JUST_NULLS){
			componentType = res.constantType();
			componentIOType = IOType.of(componentType);//TODO make constant type a generic
		}else{
			componentType = null;
			componentIOType = null;
		}
		
		BitInputStream nullBuffer;
		byte[]         nullBufferBytes;
		if(layout != CollectionInfo.Layout.JUST_NULLS && res.hasNulls() && res.length()>0 && !(componentType != null && componentType.isEnum())){
			var bytes = BitUtils.bitsToBytes(len);
			nullBufferBytes = src.readInts1(bytes);
			nullBuffer = new BitInputStream(new ContentInputStream.BA(nullBufferBytes), len);
		}else{
			nullBuffer = null;
			nullBufferBytes = null;
		}
		
		
		if(layout == CollectionInfo.Layout.JUST_NULLS){
			var generator = createGenerator(res, null);
			for(int i = 0, l = res.length(); i<l; i++){
				generator.add(null);
			}
			return generator.export();
		}
		
		if(layout == CollectionInfo.Layout.DYNAMIC){
			return dynamicRead(provider, src, genericContext, res, nullBufferBytes);
		}
		
		assert componentType != null;
		
		var pTypO = SupportedPrimitive.get(componentType);
		if(pTypO.isPresent()){
			return primitiveRead(src, res, pTypO.get(), nullBufferBytes);
		}
		
		if(componentType.isEnum()){
			return enumRead(src, res);
		}
		
		if(IOInstance.isInstance(componentType)){
			return instanceRead(provider, src, res, nullBufferBytes, genericContext);
		}
		
		//noinspection unchecked
		var wrapperType = WrapperStructs.getWrapperStruct((Class<Object>)componentType);
		if(wrapperType != null){
			return wrapperRead(provider, src, res, nullBufferBytes, wrapperType);
		}
		
		if(CollectionInfoAnalysis.isTypeCollection(componentType)){
			return collectionRead(provider, src, res, nullBufferBytes, genericContext);
		}
		
		throw new ShouldNeverHappenError("Case not handled for " + res + " with " + typ.getTypeName());
	}
	
	private static Generator createGenerator(CollectionInfo res, BitInputStream toClose){
		return switch(res){
			case CollectionInfo.ListInfo v -> {
				var     list = new ArrayList<>(res.length());
				boolean fin  = v.isUnmodifiable();
				yield new Generator(){
					@Override
					public void add(Object element){ list.add(element); }
					@Override
					public Object export() throws IOException{
						if(toClose != null){
							toClose.close();
						}
						
						if(fin){
							return List.copyOf(list);
						}
						return list;
					}
				};
			}
			case CollectionInfo.ArrayInfo v -> {
				var ct  = v.getArrayType().componentType();
				var arr = (Object[])Array.newInstance(ct, res.length());
				yield new Generator(){
					private int i;
					@Override
					public void add(Object element){ arr[i++] = element; }
					@Override
					public Object export() throws IOException{
						if(toClose != null) toClose.close();
						return arr;
					}
				};
			}
			case CollectionInfo.NullValue ignore -> new Generator(){
				@Override
				public void add(Object element){ throw new UnsupportedOperationException(); }
				@Override
				public Object export(){ return null; }
			};
			case CollectionInfo.PrimitiveArrayInfo ignore -> { throw new ShouldNeverHappenError(); }
		};
	}
	
	private static Object readPrimitiveArray(ContentReader src, int len, SupportedPrimitive pTyp) throws IOException{
		return switch(pTyp){
			case DOUBLE -> src.readFloats8(len);
			case FLOAT -> src.readFloats4(len);
			case BOOLEAN -> {
				if(len == 0) yield new boolean[0];
				try(var bitIn = new BitInputStream(src, len)){
					yield bitIn.readBits(new boolean[len]);
				}
			}
			case LONG -> {
				var arr = new long[len];
				if(len == 0) yield arr;
				var siz = FlagReader.readSingle(src, NumberSize.FLAG_INFO);
				for(int i = 0; i<arr.length; i++){
					arr[i] = siz.read(src);
				}
				yield arr;
			}
			case INT -> {
				var arr = new int[len];
				if(len == 0) yield arr;
				var siz = FlagReader.readSingle(src, NumberSize.FLAG_INFO);
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
	private static void readPrimitiveCollection(ContentReader src, int len, SupportedPrimitive pTyp, Generator dest) throws IOException{
		switch(pTyp){
			case null -> throw new NullPointerException();
			case DOUBLE -> {
				for(double v : src.readFloats8(len)){
					dest.add(v);
				}
			}
			case FLOAT -> {
				for(float v : src.readFloats4(len)){
					dest.add(v);
				}
			}
			case BOOLEAN -> {
				try(var bitIn = new BitInputStream(src, len)){
					for(boolean b : bitIn.readBits(new boolean[len])){
						dest.add(b);
					}
				}
			}
			case LONG -> {
				var siz = FlagReader.readSingle(src, NumberSize.FLAG_INFO);
				for(int i = 0; i<len; i++){
					dest.add(siz.read(src));
				}
			}
			case INT -> {
				var siz = FlagReader.readSingle(src, NumberSize.FLAG_INFO);
				var arr = new int[len];
				for(int i = 0; i<arr.length; i++){
					dest.add(siz.readInt(src));
				}
			}
			case SHORT -> {
				for(short i : src.readInts2(len)){
					dest.add(i);
				}
			}
			case CHAR -> {
				for(char c : src.readChars2(len)){
					dest.add(c);
				}
			}
			case BYTE -> {
				for(byte b : src.readInts1(len)){
					dest.add(b);
				}
			}
		}
	}
}
