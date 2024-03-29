package com.lapissea.dfs.io.bit;

import com.lapissea.dfs.exceptions.IllegalBitValue;

import java.io.IOException;


public interface BitReader{
	
	long readBits(int numOBits) throws IOException;
	
	default <T extends Enum<T>> T readEnum(EnumUniverse<T> info, boolean nullable) throws IOException{
		return info.read(this, nullable);
	}
	default void skipEnum(EnumUniverse<?> info, boolean nullable) throws IOException{
		info.readSkip(this, nullable);
	}
	default <T extends Enum<T>> T readEnum(EnumUniverse<T> info) throws IOException{
		return info.read(this);
	}
	default <T extends Enum<T>> T[] readEnums(EnumUniverse<T> info, int len) throws IOException{
		return info.read(len, this);
	}
	default void skipEnum(EnumUniverse<?> info) throws IOException{
		info.readSkip(this);
	}
	
	default boolean readBoolBit() throws IOException{
		return readBits(1) == 1;
	}
	
	default void checkNOneAndThrow(int n) throws IOException{
		int errorBit = checkNOne(n);
		if(errorBit != -1){
			throw new IllegalBitValue(errorBit);
		}
	}
	
	
	/**
	 * @return index where a zero was found. If all bits are one then -1 is returned
	 */
	default int checkNOne(int n) throws IOException{
		int read = 0;
		while(true){
			int remaining = n - read;
			if(remaining == 0) return -1;
			
			int toRead = Math.min(remaining, 63);
			
			long chunk = readBits(toRead);
			
			int zeroIndex = BitUtils.findBinaryZero(chunk, toRead);
			if(zeroIndex != -1) return read + zeroIndex;
			read += toRead;
		}
	}
	
	default void skip(int numOBits) throws IOException{
		int read = 0;
		while(true){
			int remaining = numOBits - read;
			if(remaining == 0) return;
			
			int toRead = Math.min(remaining, 63);
			readBits(toRead);
			read += toRead;
		}
	}
	
	default boolean[] readBits(boolean[] data) throws IOException{
		
		int maxBatch = 63;
		for(int start = 0; start<data.length; start += maxBatch){
			var batchSize = Math.min(data.length - start, maxBatch);
			
			long batch = readBits(batchSize);
			
			for(int i = 0; i<batchSize; i++){
				data[i + start] = ((batch >>> i)&1) == 1;
			}
		}
		return data;
	}
}
