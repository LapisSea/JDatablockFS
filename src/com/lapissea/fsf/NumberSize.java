package com.lapissea.fsf;

import com.lapissea.util.function.UnsafeConsumerOL;
import com.lapissea.util.function.UnsafeFunctionOL;

import java.io.IOException;

public enum NumberSize{
	
	BYTE(Byte.BYTES, 0xFFL, ContentInputStream::readUnsignedByte, (out, num)->out.writeByte((int)num)),
	SHORT(Short.BYTES, 0xFFFFL, ContentInputStream::readUnsignedShort, (out, num)->out.writeShort((int)num)),
	INT(Integer.BYTES, 0xFFFFFFFFL, in->in.readInt()&0xFFFFFFFFL, (out, num)->out.writeInt((int)num)),
	LONG(Long.BYTES, Long.MAX_VALUE, ContentInputStream::readLong, ContentOutputStream::writeLong);
	
	private static final NumberSize[] VALS=NumberSize.values();
	
	public static NumberSize ordinal(int index){
		return VALS[index];
	}
	
	public static NumberSize getBySize(long size){
		for(NumberSize value : VALS){
			if(value.maxSize >= size) return value;
		}
		throw new RuntimeException("Extremely large file?");
	}
	
	public final byte bytes;
	public final long maxSize;
	
	private final UnsafeFunctionOL<ContentInputStream, IOException>  reader;
	private final UnsafeConsumerOL<ContentOutputStream, IOException> writer;
	
	NumberSize(int bytes, long maxSize, UnsafeFunctionOL<ContentInputStream, IOException> reader, UnsafeConsumerOL<ContentOutputStream, IOException> writer){
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
		
		return ordinal() >= other.ordinal()?this:other;
	}
	
	public NumberSize min(NumberSize other){
		if(other==this) return this;
		
		return ordinal()<=other.ordinal()?this:other;
	}
	
	public void ensureCanFit(long num) throws BitDepthOutOfSpace{
		if(num>maxSize) throw new BitDepthOutOfSpace(this, num);
	}
}
