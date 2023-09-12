package com.lapissea.cfs.io.bit;


import com.lapissea.cfs.io.content.ContentWriter;

import java.io.IOException;

import static com.lapissea.cfs.io.bit.BitUtils.bitsToBytes;
import static com.lapissea.cfs.io.bit.BitUtils.makeMask;

public class BitOutputStream implements BitWriter<BitOutputStream>, AutoCloseable{
	
	private static final long BYTE_MASK = 0xFF;
	private static final int  MAX_BITS  = 63;
	
	private final ContentWriter dest;
	
	private long buffer;
	private int  written;
	private long totalBits;
	
	public BitOutputStream(ContentWriter dest){
		this.dest = dest;
	}
	
	@Override
	public BitOutputStream writeBits(long data, int bitCount) throws IOException{
		assert (data&makeMask(bitCount)) == data;
		
		if(bitCount + written>MAX_BITS){
			flush();
			var tmpData   = data;
			var remaining = bitCount;
			var overflow  = remaining + written - MAX_BITS;
			if(overflow>0){
				int toWrite = 8 - (written%8);
				while(toWrite<overflow) toWrite += 8;
				var a = tmpData&makeMask(toWrite);
				tmpData >>>= toWrite;
				remaining -= toWrite;
				pushBits(a, toWrite);
				flush();
				
				assert remaining + written - MAX_BITS<0;
				pushBits(tmpData, remaining);
				flush();
				return this;
			}
		}
		
		pushBits(data, bitCount);
		flush();
		
		return this;
	}
	
	private void pushBits(long data, int bitCount){
		buffer |= data<<written;
		written += bitCount;
		totalBits += bitCount;
	}
	
	private void flush() throws IOException{
		var bytesToWrite = written/8;
		switch(bytesToWrite){
			case 0 -> { return; }
			case 1 -> dest.write((int)(buffer&BYTE_MASK));
			default -> dest.writeWord(buffer, bytesToWrite);
		}
		written -= bytesToWrite*8;
		buffer >>>= bytesToWrite*8;
	}
	
	@Override
	public void close() throws IOException{
		var w           = written;
		int b           = bitsToBytes(written)*8;
		int paddingBits = b - written;
		if(paddingBits>0){
			fillNOne(paddingBits);
		}
		flush();
		assert written == 0 : w;
	}
	
	public long getTotalBits(){
		return totalBits;
	}
	
	public void requireWritten(long expected) throws IOException{
		var total = getTotalBits();
		if(total != expected){
			throw new IOException(this + " wrote " + total + " but " + expected + " was expected");
		}
	}
}
