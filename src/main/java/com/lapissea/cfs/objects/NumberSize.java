package com.lapissea.cfs.objects;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.exceptions.BitDepthOutOfSpaceException;
import com.lapissea.cfs.io.bit.BitUtils;
import com.lapissea.cfs.io.bit.EnumUniverse;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.SizeDescriptor.Fixed;
import com.lapissea.cfs.type.field.SizeDescriptor.Unknown;
import com.lapissea.util.NotNull;
import com.lapissea.util.Nullable;

import java.io.IOException;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.function.ToLongFunction;

@SuppressWarnings("unused")
public enum NumberSize{
	
	VOID('V', 0x0, 0, in->0, (out, num)->{}, in->0, (out, num)->{}),
	BYTE('B', 0xFFL, 1, ContentReader::readUnsignedInt1, (out, num)->out.writeInt1((int)num), NumberSize::RNS, NumberSize::WNS),
	SHORT('s', 0xFFFFL, 2, ContentReader::readUnsignedInt2, (out, num)->out.writeInt2((int)num), in->Utils.shortBitsToFloat(in.readInt2()), (out, num)->out.writeInt2(Utils.floatToShortBits((float)num))),
	SMALL_INT('S', 0xFFFFFFL, 3, ContentReader::readUnsignedInt3, (out, num)->out.writeInt3((int)num), NumberSize::RNS, NumberSize::WNS),
	INT('i', 0xFFFFFFFFL, 4, ContentReader::readUnsignedInt4, (out, num)->out.writeInt4((int)num), in->Float.intBitsToFloat(in.readInt2()), (out, num)->out.writeInt4(Float.floatToIntBits((float)num))),
	BIG_INT('I', 0xFFFFFFFFFFL, 5, ContentReader::readUnsignedInt5, ContentWriter::writeInt5, NumberSize::RNS, NumberSize::WNS),
	SMALL_LONG('l', 0xFFFFFFFFFFFFL, 6, ContentReader::readUnsignedInt6, ContentWriter::writeInt6, NumberSize::RNS, NumberSize::WNS),
	LONG('L', 0X7FFFFFFFFFFFFFFFL, 8, ContentReader::readInt8, ContentWriter::writeInt8, in->Double.longBitsToDouble(in.readInt8()), (out, num)->out.writeInt8(Double.doubleToLongBits(num)));
	
	private static double RNS(ContentReader src)             {throw new UnsupportedOperationException();}
	private static void WNS(ContentWriter dest, double value){throw new UnsupportedOperationException();}
	
	private interface WriterI{
		void write(ContentWriter dest, long value) throws IOException;
	}
	
	private interface ReaderI{
		long read(ContentReader src) throws IOException;
	}
	
	private interface WriterF{
		void write(ContentWriter dest, double value) throws IOException;
	}
	
	private interface ReaderF{
		double read(ContentReader src) throws IOException;
	}
	
	public static final EnumUniverse<NumberSize> FLAG_INFO=EnumUniverse.get(NumberSize.class);
	
	public static final NumberSize SMALEST_REAL=BYTE;
	public static final NumberSize LARGEST     =LONG;
	
	public static NumberSize ordinal(int index){
		return FLAG_INFO.get(index);
	}
	
	public static NumberSize bySizeVoidable(@Nullable INumber size){
		return size==null?VOID:bySize(size);
	}
	
	public static NumberSize bySize(INumber size){
		return bySize(size.getValue());
	}
	
	public static NumberSize bySize(long size){
		var value=searchSizeByVal(NumberSize::maxSize, size);
		if(value!=null) return value;
		throw new RuntimeException("Extremely large value: "+size);
	}
	
	public static NumberSize byBits(int bits){
		return byBytes(Utils.bitToByte(bits));
	}
	
	public static NumberSize byBytes(long bytes){
		var value=searchSizeByVal(NumberSize::bytes, bytes);
		if(value!=null) return value;
		throw new RuntimeException("Extremely large byte length: "+bytes);
	}
	public static <T extends IOInstance<T>> NumberSize by(SizeDescriptor.Fixed<T> size){
		var value=searchSizeByVal(NumberSize::bytes, size.get());
		if(value!=null) return value;
		
		throw new RuntimeException("Extremely large size: "+size);
	}
	public static <T extends IOInstance<T>> NumberSize by(SizeDescriptor<T> size, T instance){
		var value=searchSizeByVal(
			NumberSize::bytes,
			switch(size){
				case Fixed f -> f.get();
				case Unknown<T> u -> u.calcUnknown(instance);
			}
		);
		if(value!=null) return value;
		
		throw new RuntimeException("Extremely large size: "+size);
	}
	
	private static <T> NumberSize searchSizeByVal(ToLongFunction<NumberSize> mapper, long key){
		//TODO: maybe something more intelligent?
		for(var value : FLAG_INFO){
			if(mapper.applyAsLong(value)>=key) return value;
		}
		return null;
	}
	
	public final int  bytes;
	public final long maxSize;
	public final char shortName;
	
	private final ReaderI reader;
	private final WriterI writer;
	private final ReaderF readerFloating;
	private final WriterF writerFloating;
	
	public final OptionalInt  optionalBytes;
	public final OptionalLong optionalBytesLong;
	
	NumberSize(char shortName, long maxSize, int bytes, ReaderI reader, WriterI writer, ReaderF readerFloating, WriterF writerFloating){
		this.shortName=shortName;
		this.bytes=bytes;
		this.maxSize=maxSize;
		
		this.reader=reader;
		this.writer=writer;
		this.readerFloating=readerFloating;
		this.writerFloating=writerFloating;
		
		optionalBytes=OptionalInt.of(bytes);
		optionalBytesLong=OptionalLong.of(bytes);
	}
	
	public double readFloating(ContentReader in) throws IOException{
		return readerFloating.read(in);
	}
	
	public void writeFloating(ContentWriter out, double value) throws IOException{
		writerFloating.write(out, value);
	}
	
	public long read(ContentReader in) throws IOException{
		return reader.read(in);
	}
	
	public void write(ContentWriter out, INumber value) throws IOException{
		write(out, value==null?0:value.getValue());
	}
	
	public void write(ContentWriter out, long value) throws IOException{
		writer.write(out, value);
	}
	
	public NumberSize prev(){
		var prevId=ordinal()-1;
		if(prevId==-1) return this;
		return ordinal(prevId);
	}
	
	public NumberSize next(){
		var nextId=ordinal()+1;
		if(nextId==FLAG_INFO.size()) return this;
		return ordinal(nextId);
	}
	
	public boolean greaterThanOrEqual(NumberSize other){
		if(other==this) return false;
		return bytes>=other.bytes;
	}
	public boolean lesserThanOrEqual(NumberSize other){
		if(other==this) return false;
		return bytes<=other.bytes;
	}
	
	public boolean greaterThan(NumberSize other){
		if(other==this) return false;
		return bytes>other.bytes;
	}
	
	public boolean lesserThan(NumberSize other){
		if(other==this) return false;
		return bytes<other.bytes;
	}
	
	public NumberSize max(NumberSize other){
		return greaterThan(other)?this:other;
	}
	
	public NumberSize min(NumberSize other){
		return lesserThan(other)?this:other;
	}
	
	public boolean canFit(INumber num){
		return canFit(num.getValue());
	}
	
	public boolean canFit(long num){
		return num<maxSize;
	}
	
	public long remaining(long num){
		return maxSize-num;
	}
	
	public void ensureCanFit(@Nullable ChunkPointer num) throws BitDepthOutOfSpaceException{
		ensureCanFit(ChunkPointer.getValueNullable(num));
	}
	
	public void ensureCanFit(@NotNull INumber num) throws BitDepthOutOfSpaceException{
		ensureCanFit(num.getValue());
	}
	
	public void ensureCanFit(long num) throws BitDepthOutOfSpaceException{
		if(!canFit(num)) throw new BitDepthOutOfSpaceException(this, num);
	}
	
	public void write(ContentWriter stream, long[] data) throws IOException{
		for(var l : data){
			write(stream, l);
		}
	}
	
	public NumberSize requireNonVoid(){
		if(this==VOID) throw new RuntimeException("Value must not be "+VOID);
		return this;
	}
	
	public int bytes(){
		return bytes;
	}
	
	public int bits(){
		return bytes*Byte.SIZE;
	}
	
	public String binaryString(long value){
		var    bitCount=bits();
		String bits    =Long.toBinaryString(value&BitUtils.makeMask(bitCount));
		if(bits.length()==bitCount) return bits;
		return "0".repeat(bitCount-bits.length())+bits;
	}
	
	public long maxSize(){
		return maxSize;
	}
	public char shortName(){
		return shortName;
	}
	public OptionalInt optionalBytes(){
		return optionalBytes;
	}
	public OptionalLong optionalBytesLong(){
		return optionalBytesLong;
	}
}
