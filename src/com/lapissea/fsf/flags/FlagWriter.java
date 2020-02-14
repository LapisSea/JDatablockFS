package com.lapissea.fsf.flags;

import com.lapissea.fsf.NumberSize;
import com.lapissea.fsf.io.ContentWriter;

import java.io.IOException;

public class FlagWriter{
	
	public static <T extends Enum<T>> int enumBits(T val){
		return enumBits(val.getDeclaringClass());
	}
	
	public static int enumBits(Class<? extends Enum<?>> type){
		var consts=type.getEnumConstants();
		var size  =consts.length;
		return Math.max(1, (int)Math.ceil(Math.log(size)/Math.log(2)));
	}
	
	private final NumberSize numberSize;
	private       long       buffer;
	private       int        written;
	
	public FlagWriter(NumberSize numberSize){
		this.numberSize=numberSize;
	}
	
	public FlagWriter writeBoolBit(boolean bool){
		writeBits(bool?1:0, 1);
		return this;
	}
	
	
	public <T extends Enum<T>> FlagWriter writeEnum(T val){
		writeBits(val.ordinal(), enumBits(val));
		return this;
	}
	
	public FlagWriter writeBits(long data, int bitCount){
		if(written+bitCount>numberSize.bytes*Byte.SIZE) throw new RuntimeException("ran out of bits");
		
		var safeData=data&makeMask(bitCount);
		buffer|=safeData<<written;
		
		written+=bitCount;
		return this;
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
	
	public FlagWriter fillRestAllOne(){
		while(remainingCount()>0){
			writeBoolBit(true);
		}
		return this;
	}
}
