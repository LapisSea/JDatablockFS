package com.lapissea.fsf;

import java.io.IOException;

public class FlagReader{
	
	private long data;
	private int  bitCount;
	
	public static FlagReader read(ContentInputStream in, NumberSize size) throws IOException{
		return new FlagReader(size.read(in), size.bytes*Byte.SIZE);
	}
	
	public FlagReader(long data, NumberSize size){
		this(data, size.bytes*Byte.SIZE);
	}
	
	public FlagReader(long data, int bitCount){
		this.data=data;
		this.bitCount=bitCount;
	}
	
	
	public <T extends Enum<T>> T readEnum(Class<T> type){
		var consts=type.getEnumConstants();
		var size  =consts.length;
		
		int bits=Math.max(1, (int)Math.ceil(Math.log(size)/Math.log(2)));
		int ordinal=readBits(bits);
		return consts[ordinal];
	}
	
	public boolean readBoolBit(){
		return readBits(1)==1;
	}
	
	public int readBits(int numOBits){
		if(bitCount<numOBits) throw new RuntimeException("ran out of bits");
		bitCount-=numOBits;
		
		var result=data&makeMask(numOBits);
		data >>>= numOBits;
		return (int)result;
	}
	
	private long makeMask(int size){
		return (1<<size)-1;
	}
}
