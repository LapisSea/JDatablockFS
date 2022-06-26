package com.lapissea.cfs.io.bit;

import com.lapissea.cfs.exceptions.IllegalBitValueException;
import com.lapissea.cfs.io.content.ContentReader;

import java.io.IOException;

import static com.lapissea.cfs.io.bit.BitUtils.bitsToBytes;
import static com.lapissea.cfs.io.bit.BitUtils.makeMask;

public class BitInputStream implements BitReader, AutoCloseable{
	
	private final ContentReader source;
	
	private long buffer;
	private int  bufferedBits;
	private long totalBits;
	
	private final long expectedBits;
	
	public BitInputStream(ContentReader source, long expectedBits){
		this.source=source;
		this.expectedBits=expectedBits;
	}
	
	private void prepareBits(int numOBits) throws IOException{
		
		int bitsToRead=numOBits-bufferedBits;
		if(bitsToRead>0){
			int toRead=bitsToBytes(bitsToRead);
			
			if(expectedBits>0){
				int remainingExpected=(int)(expectedBits-totalBits);
				if(remainingExpected>0){
					var alreadyBuffered=bitsToBytes(bufferedBits);
					var maxToRead      =8-alreadyBuffered;
					toRead=Math.min(Math.max(toRead, remainingExpected/8), maxToRead);
				}
			}
			
			if(toRead==1){
				buffer|=(long)source.tryRead()<<bufferedBits;
				bufferedBits+=Byte.SIZE;
			}else{
				buffer|=Long.reverseBytes(source.readWord(toRead)<<((8-toRead)*8))<<bufferedBits;
				bufferedBits+=toRead*Byte.SIZE;
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
		var start=totalBits;
		try{
			checkNOneAndThrow(bufferedBits);
		}catch(IllegalBitValueException e){
			var b=e.bit+start;
			throw new IllegalBitValueException(b, "Illegal bit found at "+b, e);
		}
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
