package com.lapissea.cfs.io.bit;


import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.objects.NumberSize;

import java.io.IOException;

import static com.lapissea.cfs.GlobalConfig.*;
import static com.lapissea.cfs.io.bit.BitUtils.*;

public class FlagWriter implements BitWriter{
	
	public static class AutoPop extends FlagWriter implements AutoCloseable{
		private final ContentWriter dest;
		
		public AutoPop(NumberSize numberSize, ContentWriter dest){
			super(numberSize);
			this.dest=dest;
		}
		
		@Override
		public void close() throws IOException{
			fillRestAllOne().export(dest);
		}
	}
	
	private static final ThreadLocal<FlagWriter> INTERNAL_WRITERS=ThreadLocal.withInitial(()->new FlagWriter(NumberSize.VOID));
	
	public static <T extends Enum<T>> void writeSingle(ContentWriter target, EnumUniverse<T> enumInfo, boolean nullable, T value) throws IOException{
		writeSingle(target, enumInfo.numSize(nullable), enumInfo, nullable, value);
	}
	
	public static <T extends Enum<T>> void writeSingle(ContentWriter target, NumberSize size, EnumUniverse<T> enumInfo, boolean nullable, T value) throws IOException{
		if(DEBUG_VALIDATION){
			var nums=enumInfo.numSize(nullable);
			if(nums.lesserThan(size)) throw new IllegalArgumentException(nums+" <= "+size);
		}
		
		FlagWriter flags=INTERNAL_WRITERS.get();
		flags.reset(size);
		
		flags.writeEnum(enumInfo, value, nullable);
		
		flags.fillRestAllOne().export(target);
	}
	
	private NumberSize numberSize;
	private long       buffer;
	private int        written;
	
	public FlagWriter(NumberSize numberSize){
		reset(numberSize);
	}
	
	private void reset(NumberSize numberSize){
		this.numberSize=numberSize;
		written=0;
		buffer=0;
	}
	
	@Override
	public FlagWriter writeBits(long data, int bitCount){
		assert (data&makeMask(bitCount))==data;
		if(written+bitCount>numberSize.bits()) throw new RuntimeException("ran out of bits "+(written+bitCount)+" > "+numberSize.bits());
		
		buffer|=data<<written;
		written+=bitCount;
		
		return this;
	}
	
	public FlagWriter fillRestAllOne() throws IOException{
		fillNOne(remainingCount());
		return this;
	}
	
	public int remainingCount(){
		return numberSize.bytes*Byte.SIZE-written;
	}
	
	public void export(ContentWriter dest) throws IOException{
		numberSize.write(dest, buffer);
	}
	
	@Override
	public String toString(){
		StringBuilder sb=new StringBuilder(remainingCount());
		sb.append(Long.toBinaryString(buffer));
		while(sb.length()<numberSize.bytes*Byte.SIZE) sb.insert(0, '-');
		return sb.toString();
	}
}
