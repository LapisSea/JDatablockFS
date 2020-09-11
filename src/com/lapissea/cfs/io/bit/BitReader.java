package com.lapissea.cfs.io.bit;

import com.lapissea.cfs.exceptions.IllegalBitValueException;

import java.util.function.IntFunction;

import static com.lapissea.cfs.io.bit.BitUtils.*;

public interface BitReader{
	
	int readBits(int numOBits);
	
	default <T extends Enum<T>> T readEnum(Class<T> type){
		return readEnum(EnumFlag.get(type));
	}
	default <T extends Enum<T>> T readEnum(EnumFlag<T> info){
		return info.read(this);
	}
	
	default boolean readBoolBit(){
		return readBits(1)==1;
	}
	
	default void checkNOneAndThrow(int n){
		checkNOneAndThrow(n, bit->"Illegal bit at "+bit);
	}
	
	default void checkNOneAndThrow(int n, IntFunction<String> message){
		int errorBit=checkNOne(n);
		if(errorBit!=-1) throw new IllegalBitValueException(message.apply(errorBit));
	}
	
	
	/**
	 * @return index where a zero was found. If all bits are one then -1 is returned
	 */
	default int checkNOne(int n){
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
	
}
