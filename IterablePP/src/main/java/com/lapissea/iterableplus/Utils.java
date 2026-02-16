package com.lapissea.iterableplus;

import java.util.Arrays;
import java.util.OptionalInt;

final class Utils{
	
	static int[] growArr(int[] res){
		return Arrays.copyOf(res, calcNextSize(res.length));
	}
	static char[] growArr(char[] res){
		return Arrays.copyOf(res, calcNextSize(res.length));
	}
	static long[] growArr(long[] res){
		return Arrays.copyOf(res, calcNextSize(res.length));
	}
	static <T> T[] growArr(T[] res){
		return Arrays.copyOf(res, calcNextSize(res.length));
	}
	private static int calcNextSize(int length){
		long nextSizeL = length*2L;
		if(nextSizeL != (int)nextSizeL){
			if(length == Integer.MAX_VALUE){
				throw new OutOfMemoryError();
			}
			nextSizeL = Integer.MAX_VALUE;
		}
		return (int)nextSizeL;
	}
	public static OptionalInt longToOptInt(long lSize){
		if(lSize>Integer.MAX_VALUE) return OptionalInt.empty();
		return OptionalInt.of((int)lSize);
	}
}
