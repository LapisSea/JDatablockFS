package com.lapissea.fsf;

import com.lapissea.util.function.UnsafeConsumerOL;
import com.lapissea.util.function.UnsafeFunctionOL;

import java.io.IOException;

public enum NumberSize{
	
	BYTE('B', 0xFFL, Byte.BYTES, ContentInputStream::readUnsignedByte, (out, num)->out.writeByte((int)num)),
	SHORT('S', 0xFFFFL, Short.BYTES, ContentInputStream::readUnsignedShort, (out, num)->out.writeShort((int)num)),
	INT('I', 0xFFFFFFFFL, Integer.BYTES, in->in.readInt()&0xFFFFFFFFL, (out, num)->out.writeInt((int)num)),
	LONG('L', Long.MAX_VALUE, Long.BYTES, ContentInputStream::readLong, ContentOutputStream::writeLong);
	
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
	
	private final UnsafeFunctionOL<ContentInputStream, IOException>  reader;
	private final UnsafeConsumerOL<ContentOutputStream, IOException> writer;
	
	NumberSize(char shotName, long maxSize, int bytes, UnsafeFunctionOL<ContentInputStream, IOException> reader, UnsafeConsumerOL<ContentOutputStream, IOException> writer){
		this.shotName=shotName;
		this.bytes=(byte)bytes;
		this.maxSize=maxSize;
		this.reader=reader;
		this.writer=writer;
	}
	
	public long read(ContentInputStream in) throws IOException{
		return reader.apply(in);
	}
	
	public void write(ContentOutputStream out, long value) throws IOException{
		writer.accept(out, value);
	}
	
	public NumberSize next(){
		if(VALS[VALS.length-1]==this) return this;
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
		return num<=maxSize;
	}
	
	public void ensureCanFit(long num) throws BitDepthOutOfSpaceException{
		if(!canFit(num)) throw new BitDepthOutOfSpaceException(this, num);
	}
	
	public void write(ContentOutputStream stream, long[] data) throws IOException{
		for(var l : data){
			write(stream, l);
		}
	}
	
}
