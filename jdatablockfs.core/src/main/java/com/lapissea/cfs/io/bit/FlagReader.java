package com.lapissea.cfs.io.bit;


import com.lapissea.cfs.exceptions.IllegalBitValue;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.util.NotNull;

import java.io.IOException;

import static com.lapissea.cfs.io.bit.BitUtils.makeMask;

public class FlagReader implements BitReader, AutoCloseable{
	
	public static boolean readSingleBool(ContentReader in) throws IOException{
		var b = in.readUnsignedInt1();
		if((b|1) != 255) failBool(b);
		return (b&1) == 1;
	}
	private static void failBool(int b){
		for(int i = 1; i<8; i++){
			if(((b>>i)&1) == 0) throw new IllegalBitValue(i);
		}
		throw new IllegalBitValue(-1);
	}
	
	@NotNull
	public static <T extends Enum<T>> T readSingle(ContentReader in, EnumUniverse<T> enumInfo) throws IOException{
		var size = enumInfo.numSize(false);
		
		if(size == NumberSize.BYTE){
			int data = in.readUnsignedInt1();
			
			var eSiz          = enumInfo.bitSize;
			int integrityBits = ((1<<eSiz) - 1)<<eSiz;
			
			if((data&integrityBits) != integrityBits){
				throw new IllegalBitValue(BitUtils.binaryRangeFindZero(data, 8, 0));
			}
			
			return enumInfo.get((int)(data&((1L<<eSiz) - 1L)));
		}
		
		
		try(var flags = new FlagReader(size.read(in), size.bits())){
			return flags.readEnum(enumInfo);
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
		totalBitCount = bitCount;
		this.data = data;
		this.bitCount = bitCount;
	}
	
	public int remainingCount(){
		return bitCount;
	}
	
	@Override
	public long readBits(int numOBits) throws IOException{
		if(bitCount<numOBits) throw new IOException("ran out of bits");
		
		var result = (data&makeMask(numOBits));
		data >>>= numOBits;
		bitCount -= numOBits;
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
		var  sb  = new StringBuilder(totalBitCount);
		long buf = data;
		for(int i = 0; i<bitCount; i++){
			sb.append((int)((buf>>(bitCount - i - 1))&1));
		}
		sb.append("~".repeat(Math.max(0, readCount())));
		return sb.toString();
	}
	
	public int readCount(){
		return totalBitCount - bitCount;
	}
}
