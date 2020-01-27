package com.lapissea.fsf.flags;

import com.lapissea.fsf.io.ContentWriter;
import com.lapissea.fsf.NumberSize;

import java.io.IOException;

public class FlagWriter{
	
	private final NumberSize numberSize;
	private       long       buffer;
	private       int        written;
	
	public FlagWriter(NumberSize numberSize){
		this.numberSize=numberSize;
	}
	
	public void writeBoolBit(boolean bool){
		writeBits(bool?1:0, 1);
	}
	
	
	public <T extends Enum<T>> void writeEnum(T val){
		var consts=val.getDeclaringClass().getEnumConstants();
		var size  =consts.length;
		var bits  =Math.max(1, (int)Math.ceil(Math.log(size)/Math.log(2)));
		writeBits(val.ordinal(), bits);
	}
	
	public void writeBits(long data, int bitCount){
		if(written+bitCount>numberSize.bytes*Byte.SIZE) throw new RuntimeException("ran out of bits");
		
		var safeData=data&makeMask(bitCount);
		buffer|=safeData<<written;
		
		written+=bitCount;
	}
	
	public int remainingCount(){
		return numberSize.bytes*Byte.SIZE-written;
	}
	
	public long getFlags(){
		return buffer;
	}
	
	public void export(ContentWriter dest) throws IOException{
		numberSize.write(dest, getFlags());
	}
	
	private long makeMask(int size){
		return (1<<size)-1;
	}
	
	public void fillRestAllOne(){
		while(remainingCount()>0){
			writeBoolBit(true);
		}
	}
}
