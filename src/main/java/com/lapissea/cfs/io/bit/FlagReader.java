package com.lapissea.cfs.io.bit;


import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.objects.NumberSize;

import java.io.IOException;

import static com.lapissea.cfs.io.bit.BitUtils.*;

public class FlagReader implements BitReader, AutoCloseable{
	
	
	public static <T extends Enum<T>> T readSingle(ContentReader in, EnumFlag<T> enumInfo) throws IOException{
		return readSingle(in, NumberSize.byBits(enumInfo.bitSize), enumInfo);
	}
	
	public static <T extends Enum<T>> T readSingle(ContentReader in, NumberSize size, EnumFlag<T> enumInfo) throws IOException{
		try(var flags=read(in, size)){
			return flags.readEnum(enumInfo);
		}
	}
	
	public static FlagReader read(ContentReader in, NumberSize size) throws IOException{
		return new FlagReader(size.read(in), size.bytes*Byte.SIZE);
	}
	
	private final int  totalBitCount;
	private       long data;
	private       int  bitCount;
	
	public FlagReader(long data, NumberSize size){
		this(data, size.bytes*Byte.SIZE);
	}
	
	public FlagReader(long data, int bitCount){
		this.data=data;
		this.bitCount=bitCount;
		totalBitCount=bitCount;
	}
	
	public int remainingCount(){
		return bitCount;
	}
	
	@Override
	public long readBits(int numOBits) throws IOException{
		if(bitCount<numOBits) throw new IOException("ran out of bits");
		
		var result=(data&makeMask(numOBits));
		data >>>= numOBits;
		bitCount-=numOBits;
		return result;
	}
	
	
	@Override
	public void checkNOneAndThrow(int n) throws IOException{
		int readBits=readCount();
		checkNOneAndThrow(n, bit->"Illegal bit at "+(readBits+bit));
	}
	
	public void checkRestAllOneAndThrow() throws IOException{
		checkNOneAndThrow(remainingCount());
	}
	
	@Override
	public void close() throws IOException{
		checkRestAllOneAndThrow();
	}
	
	@Override
	public String toString(){
		StringBuilder sb=new StringBuilder(remainingCount());
		sb.append(Long.toBinaryString(data));
		while(sb.length()<remainingCount()) sb.insert(0, '-');
		return sb.toString();
	}
	
	public int readCount(){
		return totalBitCount-bitCount;
	}
}
