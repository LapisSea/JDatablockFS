package com.lapissea.cfs.io.bit;


import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.objects.NumberSize;

import java.io.IOException;

import static com.lapissea.cfs.GlobalConfig.DEBUG_VALIDATION;
import static com.lapissea.cfs.io.bit.BitUtils.makeMask;

public class FlagReader implements BitReader, AutoCloseable{
	
	
	private static final ThreadLocal<FlagReader> INTERNAL_READERS=ThreadLocal.withInitial(()->new FlagReader(0, -1));
	
	public static <T extends Enum<T>> T readSingle(ContentReader in, EnumUniverse<T> enumInfo, boolean nullable) throws IOException{
		return readSingle(in, enumInfo.numSize(nullable), enumInfo, nullable);
	}
	
	public static <T extends Enum<T>> T readSingle(ContentReader in, NumberSize size, EnumUniverse<T> enumInfo, boolean nullable) throws IOException{
		if(DEBUG_VALIDATION){
			var nums=enumInfo.numSize(nullable);
			if(nums.lesserThan(size)) throw new IllegalArgumentException(nums+" <= "+size);
		}
		
		try(var flags=INTERNAL_READERS.get().start(size.read(in), size.bits())){
			return flags.readEnum(enumInfo, nullable);
		}
	}
	
	public static FlagReader read(ContentReader in, NumberSize size) throws IOException{
		return new FlagReader(size.read(in), size);
	}
	
	private final int  totalBitCount;
	private       long data;
	private       int  bitCount;
	
	public FlagReader(long data, NumberSize size){
		this(data, size.bits());
	}
	
	public FlagReader(long data, int bitCount){
		totalBitCount=bitCount;
		start(data, bitCount);
	}
	
	private FlagReader start(long data, int bitCount){
		this.data=data;
		this.bitCount=bitCount;
		return this;
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
