package com.lapissea.cfs.io.bit;


import com.lapissea.cfs.io.content.ContentWriter;

import java.io.IOException;

import static com.lapissea.cfs.io.bit.BitUtils.*;

public class BitOutputStream implements BitWriter<BitOutputStream>, AutoCloseable{
	
	private final ContentWriter dest;
	
	private long buffer;
	private int  written;
	private long totalBits;
	
	public BitOutputStream(ContentWriter dest){
		this.dest=dest;
	}
	
	@Override
	public BitOutputStream writeBits(long data, int bitCount) throws IOException{
		assert (data&makeMask(bitCount))==data;
		
		buffer|=data<<written;
		written+=bitCount;
		totalBits+=bitCount;
		
		while(written>=8){
			int result=(int)(buffer&makeMask(8));
			buffer >>>= 8;
			written-=8;
			
			dest.write(result);
		}
		
		return this;
	}
	
	@Override
	public void close() throws IOException{
		if(written>0){
			assert written<8;
			int paddingBits=8-written;
			fillNOne(paddingBits);
		}
	}
	
	public long getTotalBits(){
		return totalBits;
	}
	
	public void requireWritten(long expected) throws IOException{
		var total=getTotalBits();
		if(total!=expected){
			throw new IOException(this+" wrote "+total+" but "+expected+" was expected");
		}
	}
}
