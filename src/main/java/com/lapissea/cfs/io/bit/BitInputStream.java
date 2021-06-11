package com.lapissea.cfs.io.bit;

import com.lapissea.cfs.io.content.ContentReader;

import java.io.EOFException;
import java.io.IOException;

import static com.lapissea.cfs.io.bit.BitUtils.*;

public class BitInputStream implements BitReader, AutoCloseable{
	
	private final ContentReader source;
	
	private long buffer;
	private int  bufferedBits;
	private long totalBits;
	
	public BitInputStream(ContentReader source){
		this.source=source;
	}
	
	private void prepareBits(int numOBits) throws IOException{
		
		int bitsToRead=numOBits-bufferedBits;
		if(bitsToRead>0){
			int toRead=bitsToBytes(bitsToRead);
			
			if(toRead==1){
				int byt=source.read();
				if(byt==-1) throw new EOFException();
				
				buffer|=(long)byt<<bufferedBits;
				bufferedBits+=Byte.SIZE;
			}else{
				for(byte byt : source.readInts1(toRead)){
					buffer|=(long)byt<<bufferedBits;
					bufferedBits+=Byte.SIZE;
				}
				
			}
		}
	}
	
	@Override
	public long readBits(int numOBits) throws IOException{
		prepareBits(numOBits);
		if(numOBits>bufferedBits) throw new IOException("ran out of bits");
		
		var result=(buffer&makeMask(numOBits));
		buffer >>>= numOBits;
		bufferedBits-=numOBits;
		totalBits+=numOBits;
		return result;
	}
	
	@Override
	public void close() throws IOException{
		checkNOneAndThrow(bufferedBits);
	}
	
	public long getTotalBits(){
		return totalBits;
	}
	
	public void requireRead(long expected) throws IOException{
		var total=getTotalBits();
		if(total!=expected){
			throw new IOException(this+" read "+total+" but "+expected+" was expected");
		}
	}
}
