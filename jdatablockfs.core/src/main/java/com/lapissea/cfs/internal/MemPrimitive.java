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
		if(MyUnsafe.IS_LITTLE_ENDIAN) return getWordLE(data, off, len);
		return getWordLoop(data, off, len);
	}
	
	public static void setWord(long v, byte[] writeBuffer, int off, int len){
		if(MyUnsafe.IS_LITTLE_ENDIAN) setWordLE(v, writeBuffer, off, len);
		setWordLoop(v, writeBuffer, off, len);
	}
	
	private static long getWordLE(byte[] data, int off, int len){
		if(len == 8){
			Objects.checkFromIndexSize(off, len, data.length);
			return UNSAFE.getLong(data, OFF_START + off);
		}
		
		return getWordLoop(data, off, len);
	}
	
	private static void setWordLE(long v, byte[] writeBuffer, int off, int len){
		if(v == 0){
			for(int i = off, j = off + len; i<j; i++){
				writeBuffer[i] = 0;
			}
			return;
		}
		if(len == 8){
			Objects.checkFromIndexSize(off, len, writeBuffer.length);
			UNSAFE.putLong(writeBuffer, OFF_START + off, v);
			return;
		}
		if(len == 4){
			Objects.checkFromIndexSize(off, len, writeBuffer.length);
			UNSAFE.putInt(writeBuffer, OFF_START + off, (int)v);
			return;
		}
		
		setWordLoop(v, writeBuffer, off, len);
	}
	
	private static long getWordLoop(byte[] data, int off, int len){
		long val = 0;
		for(int i = 0; i<len; i++){
			val |= (data[off + i]&255L)<<(i*8);
		}
		return val;
	}
	private static void setWordLoop(long v, byte[] writeBuffer, int off, int len){
		for(int i = 0; i<len; i++){
			writeBuffer[off + i] = (byte)(v >>> (i*8));
		}
	}
}
