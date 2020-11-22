package com.lapissea.cfs.io.bit;

import com.lapissea.cfs.exceptions.IllegalBitValueException;

import java.io.IOException;
import java.util.function.IntFunction;

import static com.lapissea.cfs.io.bit.BitUtils.*;

public interface BitReader{
	
	long readBits(int numOBits) throws IOException;
	
	default <T extends Enum<T>> T readEnum(EnumUniverse<T> info, boolean nullable) throws IOException{
		return info.read(this, nullable);
	}
	
	default boolean readBoolBit() throws IOException{
		return readBits(1)==1;
	}
	
	default void checkNOneAndThrow(int n) throws IOException{
		checkNOneAndThrow(n, bit->"Illegal bit at "+bit);
	}
	
	default void checkNOneAndThrow(int n, IntFunction<String> message) throws IOException{
		int errorBit=checkNOne(n);
		if(errorBit!=-1) throw new IllegalBitValueException(message.apply(errorBit));
	}
	
	
	/**
	 * @return index where a zero was found. If all bits are one then -1 is returned
	 */
	default int checkNOne(int n) throws IOException{
		int read=0;
		while(true){
			int remaining=n-read;
			if(remaining==0) return -1;
			
			int toRead=Math.min(remaining, 63);
			
			long chunk=readBits(toRead);
			
			int zeroIndex=binaryRangeFindZero(chunk, toRead, read);
			if(zeroIndex!=-1) return read+zeroIndex;
			read+=toRead;
		}
	}
	
	default void skip(int numOBits) throws IOException{
		int read=0;
		while(true){
			int remaining=numOBits-read;
			if(remaining==0) return;
			
			int toRead=Math.min(remaining, 63);
			readBits(toRead);
			read+=toRead;
		}
	}
	
	default boolean[] readBits(boolean[] data) throws IOException{
		for(int i=0;i<data.length;i++){
			data[i]=readBoolBit();
		}
		return data;
	}
}
