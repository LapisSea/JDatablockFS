package com.lapissea.cfs.internal;

import java.util.Objects;

import static com.lapissea.cfs.internal.MyUnsafe.UNSAFE;

public class MemPrimitive{
	
	private static final int OFF_START  =UNSAFE.arrayBaseOffset(byte[].class);
	private static final int INDEX_SCALE=UNSAFE.arrayIndexScale(byte[].class);
	private static final int BARR_OFF   =MyUnsafe.arrayStart(byte[].class);
	
	
	private static long calcOff(final int offset){
		return OFF_START+(long)offset*INDEX_SCALE;
	}
	
	private static void checkRange(final byte[] bb, final int offset, final int size){
		if(offset<0){
			throw new IndexOutOfBoundsException();
		}
		if(offset+size>bb.length){
			throw new IndexOutOfBoundsException();
		}
	}
	
	public static long getLong(final byte[] bb, final int offset){
		checkRange(bb, offset, Long.BYTES);
		return UNSAFE.getLong(bb, calcOff(offset));
	}
	public static void setLong(final byte[] bb, final int offset, long val){
		checkRange(bb, offset, Long.BYTES);
		UNSAFE.putLong(bb, calcOff(offset), val);
	}
	
	public static int getInt(final byte[] bb, final int offset){
		checkRange(bb, offset, Integer.BYTES);
		return UNSAFE.getInt(bb, calcOff(offset));
	}
	public static void setInt(final byte[] bb, final int offset, int val){
		checkRange(bb, offset, Integer.BYTES);
		UNSAFE.putInt(bb, calcOff(offset), val);
	}
	
	public static byte getByte(final byte[] bb, final int offset){
		checkRange(bb, offset, Byte.BYTES);
		return UNSAFE.getByte(bb, calcOff(offset));
	}
	public static void setByte(final byte[] bb, final int offset, byte val){
		checkRange(bb, offset, Byte.BYTES);
		UNSAFE.putByte(bb, calcOff(offset), val);
	}
	
	public static long getWord(byte[] data, int off, int len){
		if(!MyUnsafe.IS_BIG_ENDIAN&&len==8){
			Objects.checkFromIndexSize(off, len, data.length);
			return Long.reverseBytes(UNSAFE.getLong(data, BARR_OFF+off));
		}
		
		final var lm1=len-1;
		long      val=0;
		for(int i=0;i<len;i++){
			val|=(data[off+i]&255L)<<((lm1-i)*8);
		}
		return val;
	}
	
	public static void setWord(long v, byte[] writeBuffer, int off, int len){
		if(v==0){
			for(int i=off, j=off+len;i<j;i++){
				writeBuffer[i]=0;
			}
			return;
		}
		
		if(!MyUnsafe.IS_BIG_ENDIAN&&len==8){
			Objects.checkFromIndexSize(off, len, writeBuffer.length);
			UNSAFE.putLong(writeBuffer, BARR_OFF+off, Long.reverseBytes(v));
			return;
		}
		
		final var lm1=len-1;
		
		for(int i=0;i<len;i++){
			writeBuffer[off+i]=(byte)(v >>> ((lm1-i)*8));
		}
	}
}
