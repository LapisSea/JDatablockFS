package com.lapissea.cfs.io.bit;

import java.util.OptionalLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class BitUtils{
	
	public static int binaryRangeFindZero(long chunk, int bits, int offset){
		
		if(bits == 1){
			if((chunk&1) == 0) return offset;
			else return -1;
		}
		
		int  leftSize = bits/2;
		long leftMask = (1L<<leftSize) - 1L;
		
		if((chunk&leftMask) != leftMask){
			return binaryRangeFindZero(chunk, leftSize, offset);
		}
		
		long rightMask = ~leftMask;
		
		if((chunk&rightMask) != rightMask){
			return binaryRangeFindZero(chunk >>> leftSize, bits - leftSize, offset + leftSize);
		}
		
		return -1;
	}
	
	public static long makeMask(int size){
		assert size<=64 : "mask size must be 64 or less but was " + size;
		assert size>=0 : "mask size must be positive but was " + size;
		
		if(size == 64) return -1;
		return (1L<<size) - 1L;
	}
	
	public static int bitsToBytes(int bits){
		return Math.ceilDiv(bits, Byte.SIZE);
	}
	
	public static long bitsToBytes(long bits){
		return Math.ceilDiv(bits, Byte.SIZE);
	}
	public static OptionalLong bitsToBytes(OptionalLong bits){
		return bits.isPresent()? OptionalLong.of(bitsToBytes(bits.getAsLong())) : bits;
	}
	
	public static String toBinStr(byte[] bb){
		StringBuilder sb = new StringBuilder(bb.length*8);
		StringBuilder s  = new StringBuilder(8);
		for(byte b : bb){
			s.setLength(0);
			s.append(Integer.toBinaryString(b&0xFF));
			s.reverse();
			while(s.length()<8) s.append("0");
			sb.append(s);
		}
		return sb.toString();
	}
	
	public static String byteArrayToBitString(byte[] data)            { return byteArrayToBitString(data, 0, data.length); }
	public static String byteArrayToBitString(byte[] data, int length){ return byteArrayToBitString(data, 0, length); }
	public static String byteArrayToBitString(byte[] data, int offset, int length){
		return IntStream.range(offset, offset + length)
		                .map(i -> data[i]&0xFF)
		                .mapToObj(b -> String.format("%8s", Integer.toBinaryString(b)).replace(' ', '0'))
		                .map(s -> new StringBuilder(s).reverse())
		                .collect(Collectors.joining());
	}
}
