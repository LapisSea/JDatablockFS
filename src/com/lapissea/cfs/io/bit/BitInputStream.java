package com.lapissea.cfs.io.bit;

import com.lapissea.cfs.io.content.ContentReader;

import java.io.EOFException;
import java.io.IOException;

import static com.lapissea.cfs.io.bit.BitUtils.*;

public class BitInputStream implements BitReader, AutoCloseable{
	
	private final ContentReader source;
	
	private long buffer;
	private int  bufferedBits;
	
	public BitInputStream(ContentReader source){
		this.source=source;
	}
	
	public void prepareBits(int numOBits) throws IOException{
		
		int bitsToRead=numOBits-bufferedBits;
		if(bitsToRead>0){
			int toRead=bitsToBytes(bitsToRead);
			
			if(toRead==1){
				int byt=source.read();
				if(byt==-1) throw new EOFException();
				
				buffer|=byt<<bufferedBits;
				bufferedBits+=Byte.SIZE;
			}else{
				for(byte byt : source.readInts1(toRead)){
					buffer|=byt<<bufferedBits;
					bufferedBits+=Byte.SIZE;
				}
				
			}
		}
	}
	
	@Override
	public long readBits(int numOBits){
		assert numOBits<=bufferedBits;
		
		var result=(buffer&makeMask(numOBits));
		buffer >>>= numOBits;
		bufferedBits-=numOBits;
		return result;
	}
	
	@Override
	public void close(){
		checkNOneAndThrow(bufferedBits);
	}
}
