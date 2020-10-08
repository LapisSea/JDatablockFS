package com.lapissea.cfs.objects;

import com.lapissea.cfs.exceptions.BitDepthOutOfSpaceException;
import com.lapissea.cfs.io.bit.BitUtils;
import com.lapissea.cfs.io.bit.EnumFlag;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.objects.chunk.ChunkPointer;
import com.lapissea.util.NotNull;
import com.lapissea.util.Nullable;
import com.lapissea.util.function.UnsafeConsumerOL;
import com.lapissea.util.function.UnsafeFunctionOL;

import java.io.IOException;

public enum NumberSize{
	
	VOID('V', 0x0, 0, in->0, (out, num)->{}),
	BYTE('B', 0xFFL, 1, ContentReader::readUnsignedInt1, (out, num)->out.writeInt1((int)num)),
	SHORT('s', 0xFFFFL, 2, ContentReader::readUnsignedInt2, (out, num)->out.writeInt2((int)num)),
	SMALL_INT('S', 0xFFFFFFL, 3, ContentReader::readUnsignedInt3, (out, num)->out.writeInt3((int)num)),
	INT('i', 0xFFFFFFFFL, 4, ContentReader::readUnsignedInt4, (out, num)->out.writeInt4((int)num)),
	BIG_INT('I', 0xFFFFFFFFFFL, 5, ContentReader::readUnsignedInt5, ContentWriter::writeInt5),
	SMALL_LONG('l', 0xFFFFFFFFFFFFL, 6, ContentReader::readUnsignedInt6, ContentWriter::writeInt6),
	LONG('L', 0X7FFFFFFFFFFFFFFFL, 8, ContentReader::readInt8, ContentWriter::writeInt8);
	
	private static final NumberSize[] VALS=NumberSize.values();
	
	public static final EnumFlag<NumberSize> FLAG_INFO=EnumFlag.get(NumberSize.class);
	
	public static final NumberSize SMALEST_REAL=BYTE;
	public static final NumberSize LARGEST     =VALS[VALS.length-1];
	
	public static NumberSize ordinal(int index){
		return VALS[index];
	}
	
	public static NumberSize bySizeVoidable(@Nullable INumber size){
		return size==null?VOID:bySize(size);
	}
	
	public static NumberSize bySize(INumber size){
		return bySize(size.getValue());
	}
	
	public static NumberSize bySize(long size){
		for(NumberSize value : VALS){
			if(value.maxSize>=size) return value;
		}
		throw new RuntimeException("Extremely large value");
	}
	
	public static NumberSize byBits(int bits){
		int bytes=(int)Math.ceil(bits/(double)Byte.SIZE);
		return byBytes(bytes);
	}
	
	public static NumberSize byBytes(int bytes){
		for(NumberSize value : VALS){
			if(value.bytes>=bytes) return value;
		}
		throw new RuntimeException("Extremely large value");
	}
	
	public final int  bytes;
	public final long maxSize;
	public final char shortName;
	
	private final UnsafeFunctionOL<ContentReader, IOException> reader;
	private final UnsafeConsumerOL<ContentWriter, IOException> writer;
	
	NumberSize(char shortName, long maxSize, int bytes, UnsafeFunctionOL<ContentReader, IOException> reader, UnsafeConsumerOL<ContentWriter, IOException> writer){
		this.shortName=shortName;
		this.bytes=bytes;
		this.maxSize=maxSize;
		this.reader=reader;
		this.writer=writer;
	}
	
	public long read(ContentReader in) throws IOException{
		return reader.apply(in);
	}
	
	public void write(ContentWriter out, INumber value) throws IOException{
		write(out, value==null?0:value.getValue());
	}
	
	public void write(ContentWriter out, long value) throws IOException{
		writer.accept(out, value);
	}
	
	public NumberSize prev(){
		var prevId=ordinal()-1;
		if(prevId==-1) return this;
		return ordinal(prevId);
	}
	
	public NumberSize next(){
		var nextId=ordinal()+1;
		if(nextId==VALS.length) return this;
		return ordinal(nextId);
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
	
	public int bits(){
		return bytes*Byte.SIZE;
	}
	
	public String binaryString(long value){
		var    bitCount=bits();
		String bits    =Long.toBinaryString(value&BitUtils.makeMask(bitCount));
		if(bits.length()==bitCount) return bits;
		return "0".repeat(bitCount-bits.length())+bits;
	}
}
