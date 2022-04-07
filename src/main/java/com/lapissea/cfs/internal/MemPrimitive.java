package com.lapissea.cfs.internal;

import static com.lapissea.cfs.internal.MyUnsafe.UNSAFE;

public class MemPrimitive{
	
	private static final int OFF_START  =UNSAFE.arrayBaseOffset(byte[].class);
	private static final int INDEX_SCALE=UNSAFE.arrayIndexScale(byte[].class);
	
	
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
}
