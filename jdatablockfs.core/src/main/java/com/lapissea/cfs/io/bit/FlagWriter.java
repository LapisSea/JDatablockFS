package com.lapissea.cfs.io.bit;


import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.objects.NumberSize;

import java.io.IOException;

import static com.lapissea.cfs.config.GlobalConfig.DEBUG_VALIDATION;
import static com.lapissea.cfs.io.bit.BitUtils.makeMask;

public class FlagWriter implements BitWriter<FlagWriter>{
	
	public static class AutoPop extends FlagWriter implements AutoCloseable{
		private final ContentWriter dest;
		
		public AutoPop(NumberSize numberSize, ContentWriter dest){
			super(numberSize);
			this.dest = dest;
		}
		
		@Override
		public void close() throws IOException{
			fillRestAllOne().export(dest);
		}
	}
	
	public static void writeSingleBool(ContentWriter target, boolean value) throws IOException{
		target.writeInt1((value? 0b11111111 : 0b11111110));
	}
	
	public static <T extends Enum<T>> void writeSingle(ContentWriter target, EnumUniverse<T> enumInfo, T value) throws IOException{
		var size = enumInfo.numSize(false);
		
		if(size == NumberSize.BYTE){
			var eSiz          = enumInfo.bitSize;
			int integrityBits = ((1<<eSiz) - 1)<<eSiz;
			
			target.writeInt1(value.ordinal()|integrityBits);
			return;
		}
		
		var flags = new FlagWriter(size);
		
		flags.writeEnum(enumInfo, value);
		
		flags.fillRestAllOne().export(target);
	}
	
	private final NumberSize numberSize;
	private       long       buffer;
	private       int        written;
	
	public FlagWriter(NumberSize numberSize){
		this.numberSize = numberSize;
		written = 0;
		buffer = 0;
	}
	
	
	@Override
	public FlagWriter writeBits(long data, int bitCount){
		if(DEBUG_VALIDATION){
			if((data&makeMask(bitCount)) != data) throw new IllegalArgumentException();
		}
		checkBuffer(bitCount);
		write(data, bitCount);
		return this;
	}
	
	public FlagWriter fillRestAllOne(){
		return fillNOne(remainingCount());
	}
	
	public int remainingCount(){
		return numberSize.bytes*Byte.SIZE - written;
	}
	public int writtenBitCount(){
		return written;
	}
	@Override
	public FlagWriter fillNOne(int n){
		checkBuffer(n);
		
		int maxBatch = 63;
		if(n>maxBatch){
			write(1L, 1);
			n--;
		}
		write((1L<<n) - 1L, n);
		return this;
	}
	
	private void checkBuffer(int n){
		if(written + n>numberSize.bits()) throw new RuntimeException("ran out of bits " + (written + n) + " > " + numberSize.bits());
	}
	private void write(long data, int bitCount){
		buffer |= data<<written;
		written += bitCount;
	}
	
	public void export(ContentWriter dest) throws IOException{
		numberSize.write(dest, buffer);
	}
	
	@Override
	public String toString(){
		var  bits = numberSize.bits();
		var  sb   = new StringBuilder(bits);
		long buf  = buffer;
		sb.append("~".repeat(Math.max(0, bits - written)));
		for(int i = 0; i<written; i++){
			sb.append((int)((buf>>(written - i - 1))&1));
		}
		return sb.toString();
	}
}
