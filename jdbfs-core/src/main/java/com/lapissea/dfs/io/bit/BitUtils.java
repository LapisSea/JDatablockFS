package com.lapissea.dfs.io.bit;

import com.lapissea.dfs.utils.iterableplus.Iters;

import java.util.OptionalLong;
import java.util.stream.Collectors;

public final class BitUtils{
	
	public static int findBinaryZero(long chunk, int bits){
		var mask = makeMask(bits);
		if(mask == (chunk&mask)){
			return -1;
		}
		
		for(int i = 0; i<bits; i++){
			if(((chunk>>i)&1) == 0){
				return i;
			}
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
		return Iters.range(offset, offset + length)
		            .map(i -> data[i]&0xFF)
		            .mapToObj(b -> String.format("%8s", Integer.toBinaryString(b)).replace(' ', '0'))
		            .map(s -> new StringBuilder(s).reverse())
		            .collect(Collectors.joining());
	}
}
