package com.lapissea.cfs.io.bit;

import com.lapissea.cfs.io.content.ContentWriter;

import java.io.IOException;

import static com.lapissea.cfs.io.bit.BitUtils.*;

public class BitOutputStream implements BitWriter, AutoCloseable{
	
	private final ContentWriter dest;
	
	private long buffer;
	private int  written;
	
	public BitOutputStream(ContentWriter dest){
		this.dest=dest;
	}
	
	@Override
	public BitWriter writeBits(long data, int bitCount){
		assert (data&makeMask(bitCount))==data;
		
		buffer|=data<<written;
		written+=bitCount;
		
		while(written>=8){
			int result=(int)(buffer&makeMask(8));
			buffer >>>= 8;
			written-=8;
			
			try{
				dest.write(result);
			}catch(IOException e){
				throw new RuntimeException(e);
			}
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
}
