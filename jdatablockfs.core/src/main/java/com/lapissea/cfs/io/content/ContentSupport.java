package com.lapissea.cfs.io.content;

import java.lang.invoke.VarHandle;

import static java.lang.invoke.MethodHandles.byteArrayViewVarHandle;
import static java.nio.ByteOrder.LITTLE_ENDIAN;

@SuppressWarnings({"PointlessArithmeticExpression", "PointlessBitwiseExpression"})
public final class ContentSupport{
	
	private static final VarHandle SHORT_VIEW  = var(short.class);
	private static final VarHandle CHAR_VIEW   = var(char.class);
	private static final VarHandle INT_VIEW    = var(int.class);
	private static final VarHandle LONG_VIEW   = var(long.class);
	private static final VarHandle FLOAT_VIEW  = var(float.class);
	private static final VarHandle DOUBLE_VIEW = var(double.class);
	
	private static VarHandle var(Class<?> c){ return byteArrayViewVarHandle(c.arrayType(), LITTLE_ENDIAN).withInvokeExactBehavior(); }
	
	//////////WRITE//////////
	
	public static void writeInt1(byte[] out, int off, byte value)     { out[off] = value; }
	public static void writeChar2(byte[] writeBuffer, int off, char v){ CHAR_VIEW.set(writeBuffer, off, v); }
	public static void writeInt2(byte[] writeBuffer, int off, short v){ SHORT_VIEW.set(writeBuffer, off, v); }
	public static void writeInt3(byte[] writeBuffer, int off, int v){
		writeBuffer[off + 0] = (byte)((v >>> 0)&0xFF);
		writeBuffer[off + 1] = (byte)((v >>> 8)&0xFF);
		writeBuffer[off + 2] = (byte)((v >>> 16)&0xFF);
	}
	public static void writeInt4(byte[] writeBuffer, int off, int v){ INT_VIEW.set(writeBuffer, off, v); }
	public static void writeInt5(byte[] writeBuffer, int off, long v){
		writeBuffer[off + 0] = (byte)(v >>> 0);
		writeBuffer[off + 1] = (byte)(v >>> 8);
		writeBuffer[off + 2] = (byte)(v >>> 16);
		writeBuffer[off + 3] = (byte)(v >>> 24);
		writeBuffer[off + 4] = (byte)(v >>> 32);
	}
	public static void writeInt6(byte[] writeBuffer, int off, long v){
		writeBuffer[off + 0] = (byte)(v >>> 0);
		writeBuffer[off + 1] = (byte)(v >>> 8);
		writeBuffer[off + 2] = (byte)(v >>> 16);
		writeBuffer[off + 3] = (byte)(v >>> 24);
		writeBuffer[off + 4] = (byte)(v >>> 32);
		writeBuffer[off + 5] = (byte)(v >>> 40);
	}
	public static void writeInt8(byte[] writeBuffer, int off, long v)    { LONG_VIEW.set(writeBuffer, off, v); }
	public static void writeFloat4(byte[] writeBuffer, int off, float v) { FLOAT_VIEW.set(writeBuffer, off, v); }
	public static void writeFloat8(byte[] writeBuffer, int off, double v){ DOUBLE_VIEW.set(writeBuffer, off, v); }
	
	//////////READ//////////
	
	public static char readChar2(byte[] readBuffer, int offset)      { return (char)CHAR_VIEW.get(readBuffer, offset); }
	public static short readInt2(byte[] readBuffer, int offset)      { return (short)SHORT_VIEW.get(readBuffer, offset); }
	public static int readUnsignedInt2(byte[] readBuffer, int offset){ return readChar2(readBuffer, offset); }
	public static int readUnsignedInt3(byte[] readBuffer, int offset){
		return (((readBuffer[offset + 0]&255)<<0) +
		        ((readBuffer[offset + 1]&255)<<8) +
		        ((readBuffer[offset + 2]&255)<<16));
	}
	public static int readInt4(byte[] readBuffer, int offset){ return (int)INT_VIEW.get(readBuffer, offset); }
	public static long readUnsignedInt5(byte[] readBuffer, int offset){
		return (((long)(readBuffer[offset + 0]&255)<<0) +
		        ((long)(readBuffer[offset + 1]&255)<<8) +
		        ((long)(readBuffer[offset + 2]&255)<<16) +
		        ((long)(readBuffer[offset + 3]&255)<<24) +
		        ((long)(readBuffer[offset + 4]&255)<<32));
	}
	public static long readUnsignedInt6(byte[] readBuffer, int offset){
		return (((long)(readBuffer[offset + 0]&255)<<0) +
		        ((long)(readBuffer[offset + 1]&255)<<8) +
		        ((long)(readBuffer[offset + 2]&255)<<16) +
		        ((long)(readBuffer[offset + 3]&255)<<24) +
		        ((long)(readBuffer[offset + 4]&255)<<32) +
		        ((long)(readBuffer[offset + 5]&255)<<40));
	}
	public static long readInt8(byte[] readBuffer, int offset) { return (long)LONG_VIEW.get(readBuffer, offset); }
	public static float readFloat4(byte[] readBuffer, int off) { return (float)FLOAT_VIEW.get(readBuffer, off); }
	public static double readFloat8(byte[] readBuffer, int off){ return (double)DOUBLE_VIEW.get(readBuffer, off); }
}
