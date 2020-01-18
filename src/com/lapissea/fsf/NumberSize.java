package com.lapissea.fsf;

import com.lapissea.util.function.UnsafeConsumerOL;
import com.lapissea.util.function.UnsafeFunctionOL;

import java.io.IOException;

public enum NumberSize{
	
	VOID('V', 0x0, 0, in->0, (out, num)->{}),
	BYTE('B', 0xFFL, 1, ContentReader::readUnsignedInt1, (out, num)->out.writeInt1((int)num)),
	SHORT('s', 0xFFFFL, 2, ContentReader::readUnsignedInt2, (out, num)->out.writeInt2((int)num)),
	BIG_SHORT('S', 0xFFFFFFL, 3, ContentReader::readUnsignedInt3, (out, num)->out.writeInt3((int)num)),
	INT('i', 0xFFFFFFFFL, 4, ContentReader::readUnsignedInt4, (out, num)->out.writeInt4((int)num)),
	BIG_INT('I', 0xFFFFFFFFFFL, 5, ContentReader::readUnsignedInt5, ContentWriter::writeInt5),
	SMALL_LONG('l', 0xFFFFFFFFFFFFL, 6, ContentReader::readUnsignedInt6, ContentWriter::writeInt6),
	LONG('L', 0X7FFFFFFFFFFFFFFFL, 8, ContentReader::readInt8, ContentWriter::writeInt8);
	
	private static final NumberSize[] VALS=NumberSize.values();
	
	public static NumberSize ordinal(int index){
		return VALS[index];
	}
	
	public static NumberSize bySize(long size){
		for(NumberSize value : VALS){
			if(value.maxSize >= size) return value;
		}
		throw new RuntimeException("Extremely large file?");
	}
	
	public final byte bytes;
	public final long maxSize;
	public final char shotName;
	
	private final UnsafeFunctionOL<ContentReader, IOException> reader;
	private final UnsafeConsumerOL<ContentWriter, IOException> writer;
	
	NumberSize(char shotName, long maxSize, int bytes, UnsafeFunctionOL<ContentReader, IOException> reader, UnsafeConsumerOL<ContentWriter, IOException> writer){
		this.shotName=shotName;
		this.bytes=(byte)bytes;
		this.maxSize=maxSize;
		this.reader=reader;
		this.writer=writer;
	}
	
	public long read(ContentReader in) throws IOException{
		return reader.apply(in);
	}
	
	public void write(ContentWriter out, long value) throws IOException{
		writer.accept(out, value);
	}
	
	public NumberSize next(){
		if(ordinal(VALS.length-1)==this) return this;
		return ordinal(ordinal()+1);
	}
	
	public NumberSize max(NumberSize other){
		if(other==this) return this;
		return bytes >= other.bytes?this:other;
	}
	
	public NumberSize min(NumberSize other){
		if(other==this) return this;
		return bytes<=other.bytes?this:other;
	}
	
	public boolean canFit(long num){
		return num<maxSize;
	}
	
	public void ensureCanFit(long num) throws BitDepthOutOfSpaceException{
		if(!canFit(num)) throw new BitDepthOutOfSpaceException(this, num);
	}
	
	public void write(ContentWriter stream, long[] data) throws IOException{
		for(var l : data){
			write(stream, l);
		}
	}
	
}
