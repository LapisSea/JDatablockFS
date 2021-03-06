package com.lapissea.cfs.io.bit;

public class BitUtils{
	
	public static int binaryRangeFindZero(long chunk, int bits, int offset){
		
		if(bits==1){
			if((chunk&1)==0) return offset;
			else return -1;
		}
		
		int  leftSize=bits/2;
		long leftMask=(1L<<leftSize)-1L;
		
		if((chunk&leftMask)!=leftMask){
			return binaryRangeFindZero(chunk, leftSize, offset);
		}
		
		long rightMask=~leftMask;
		
		if((chunk&rightMask)!=rightMask){
			return binaryRangeFindZero(chunk >>> leftSize, bits-leftSize, offset+leftSize);
		}
		
		return -1;
	}
	
	public static long makeMask(int size){
		if(size>64) throw new IllegalArgumentException("mask size must be 64 or less but was "+size);
		if(size<0) throw new IllegalArgumentException("mask size must be positive but was "+size);
		if(size==64) return -1;
		
		return (1L<<size)-1L;
	}
	
	public static int ceilDiv(int dividend, int divisor){
		return (int)Math.ceil(dividend/(double)divisor);
	}
	
	public static long ceilDiv(long dividend, long divisor){
		return (int)Math.ceil(dividend/(double)divisor);
	}
	
	public static int bitsToBytes(int bits){
		return ceilDiv(bits, Byte.SIZE);
	}
}
