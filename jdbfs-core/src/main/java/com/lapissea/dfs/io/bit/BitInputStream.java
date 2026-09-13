package com.lapissea.dfs.io.bit;

import com.lapissea.dfs.exceptions.IllegalBitValue;
import com.lapissea.dfs.io.content.ContentReader;

import java.io.Closeable;
import java.io.IOException;

import static com.lapissea.dfs.io.bit.BitUtils.bitsToBytes;
import static com.lapissea.dfs.io.bit.BitUtils.makeMask;

public class BitInputStream implements BitReader, Closeable{
	
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
					var expectedBytes = bitsToBytes(remainingExpected);
					bytesToRead = Math.max(bytesToRead, expectedBytes);
				}
			}
			
			// `buffer` is a 64-bit long. Reading enough bytes to overflow it would silently drop the high bits (they shift past bit 63).
			// Cap to what fits; the remaining bits are read by the split path in readBits (numOBits > bufferedBits).
			bytesToRead = Math.min(bytesToRead, (64 - bufferedBits) >>> 3);
			
			if(bytesToRead == 1){
				buffer |= (long)source.tryRead()<<bufferedBits;
				bufferedBits += Byte.SIZE;
			}else if(bytesToRead>1){
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
		// The JVM masks long shift distances by 0x3F (JLS 15.19), so `buffer >>>= 64` is a no-op.
		// Advancing by >=64 bits discards the entire 64-bit buffer.
		buffer = numOBits>=64? 0 : buffer >>> numOBits;
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
