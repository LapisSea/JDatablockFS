package com.lapissea.cfs.io.bit;

import com.lapissea.cfs.exceptions.IllegalBitValue;
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
		this.source = source;
		this.expectedBits = expectedBits;
	}
	
	private void prepareBits(int numOBits) throws IOException{
		
		int bitsToRead = numOBits - bufferedBits;
		if(bitsToRead>0){
			int bytesToRead = bitsToBytes(bitsToRead);
			
			if(expectedBits>0){
				int remainingExpected = (int)(expectedBits - (totalBits + bufferedBits));
				if(remainingExpected>0){
					var alreadyBuffered = bitsToBytes(bufferedBits);
					var maxToRead       = 8 - alreadyBuffered;
					var expectedBytes   = bitsToBytes(remainingExpected);
					bytesToRead = Math.min(Math.max(bytesToRead, expectedBytes), maxToRead);
				}
			}
			
			if(bytesToRead == 1){
				buffer |= (long)source.tryRead()<<bufferedBits;
				bufferedBits += Byte.SIZE;
			}else{
				buffer |= source.readWord(bytesToRead)<<bufferedBits;
				bufferedBits += bytesToRead*Byte.SIZE;
			}
		}
	}
	
	@Override
	public long readBits(int numOBits) throws IOException{
		prepareBits(numOBits);
		if(numOBits>bufferedBits){
			int l = bufferedBits;
			int r = numOBits - l;
			
			var lBits = buffer;
			totalBits += bufferedBits;
			buffer = 0;
			bufferedBits = 0;
			
			prepareBits(r);
			var rBits = buffer&makeMask(r);
			advance(r);
			
			return (rBits<<l)|lBits;
		}
		
		var result = (buffer&makeMask(numOBits));
		advance(numOBits);
		return result;
	}
	
	private void advance(int numOBits){
		totalBits += numOBits;
		buffer >>>= numOBits;
		bufferedBits -= numOBits;
	}
	
	@Override
	public void close() throws IOException{
		var start = totalBits;
		try{
			checkNOneAndThrow(bufferedBits);
		}catch(IllegalBitValue e){
			var b = e.bit + start;
			throw new IllegalBitValue(b, "Illegal bit found at " + b, e);
		}
	}
	
	public long getTotalBits(){
		return totalBits;
	}
	
	public void requireRead(long expected) throws IOException{
		var total = getTotalBits();
		if(total != expected){
			throw new IOException(this + " read " + total + " but " + expected + " was expected");
		}
	}
}
