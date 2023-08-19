package com.lapissea.cfs.internal;

import java.util.Objects;

import static com.lapissea.cfs.internal.MyUnsafe.UNSAFE;

public class MemPrimitive{
	private static final int OFF_START   = UNSAFE.arrayBaseOffset(byte[].class);
	private static final int INDEX_SCALE = UNSAFE.arrayIndexScale(byte[].class);
	
	private static void checkRange(final byte[] bb, final int offset, final int size){
		Objects.checkFromIndexSize(offset, size, bb.length);
	}
	
	public static long getLong(final byte[] bb, final int offset){
		checkRange(bb, offset, Long.BYTES);
		return UNSAFE.getLong(bb, OFF_START + ((long)offset)*INDEX_SCALE);
	}
	public static void setLong(final byte[] bb, final int offset, long val){
		checkRange(bb, offset, Long.BYTES);
		UNSAFE.putLong(bb, OFF_START + ((long)offset)*INDEX_SCALE, val);
	}
	
	public static int getInt(final byte[] bb, final int offset){
		checkRange(bb, offset, Integer.BYTES);
		return UNSAFE.getInt(bb, OFF_START + ((long)offset)*INDEX_SCALE);
	}
	public static void setInt(final byte[] bb, final int offset, int val){
		checkRange(bb, offset, Integer.BYTES);
		UNSAFE.putInt(bb, OFF_START + ((long)offset)*INDEX_SCALE, val);
	}
	
	public static byte getByte(final byte[] bb, final int offset){
		return bb[offset];
	}
	public static void setByte(final byte[] bb, final int offset, byte val){
		bb[offset] = val;
	}
	
	public static long getWord(byte[] data, int off, int len){
		if(!MyUnsafe.IS_BIG_ENDIAN && len == 8){
			Objects.checkFromIndexSize(off, len, data.length);
			return Long.reverseBytes(UNSAFE.getLong(data, OFF_START + off));
		}
		
		final var lm1 = len - 1;
		long      val = 0;
		for(int i = 0; i<len; i++){
			val |= (data[off + i]&255L)<<((lm1 - i)*8);
		}
		return val;
	}
	
	public static void setWord(long v, byte[] writeBuffer, int off, int len){
		if(v == 0){
			for(int i = off, j = off + len; i<j; i++){
				writeBuffer[i] = 0;
			}
			return;
		}
		if(!MyUnsafe.IS_BIG_ENDIAN){
			if(len == 8){
				Objects.checkFromIndexSize(off, len, writeBuffer.length);
				UNSAFE.putLong(writeBuffer, OFF_START + off, Long.reverseBytes(v));
				return;
			}
			if(len == 4){
				Objects.checkFromIndexSize(off, len, writeBuffer.length);
				UNSAFE.putInt(writeBuffer, OFF_START + off, Integer.reverseBytes((int)v));
				return;
			}
		}
		
		final var lm1 = len - 1;
		
		for(int i = 0; i<len; i++){
			writeBuffer[off + i] = (byte)(v >>> ((lm1 - i)*8));
		}
	}
}
