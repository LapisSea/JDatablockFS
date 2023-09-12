package com.lapissea.cfs.io.content;

import java.lang.invoke.VarHandle;

import static java.lang.invoke.MethodHandles.byteArrayViewVarHandle;
import static java.nio.ByteOrder.LITTLE_ENDIAN;

@SuppressWarnings({"PointlessArithmeticExpression", "PointlessBitwiseExpression"})
public final class BBView{
	
	private static final VarHandle SHORT_VIEW  = var(short.class);
	private static final VarHandle CHAR_VIEW   = var(char.class);
	private static final VarHandle INT_VIEW    = var(int.class);
	private static final VarHandle LONG_VIEW   = var(long.class);
	private static final VarHandle FLOAT_VIEW  = var(float.class);
	private static final VarHandle DOUBLE_VIEW = var(double.class);
	
	private static VarHandle var(Class<?> c){ return byteArrayViewVarHandle(c.arrayType(), LITTLE_ENDIAN).withInvokeExactBehavior(); }
	
	//////////WRITE//////////
	
	public static void writeInt1(byte[] out, int off, byte value)    { out[off] = value; }
	public static void writeChar2(byte[] byteBuffer, int off, char v){ CHAR_VIEW.set(byteBuffer, off, v); }
	public static void writeInt2(byte[] byteBuffer, int off, short v){ SHORT_VIEW.set(byteBuffer, off, v); }
	public static void writeInt3(byte[] byteBuffer, int off, int v){
		byteBuffer[off + 0] = (byte)((v >>> 0)&0xFF);
		byteBuffer[off + 1] = (byte)((v >>> 8)&0xFF);
		byteBuffer[off + 2] = (byte)((v >>> 16)&0xFF);
	}
	public static void writeInt4(byte[] byteBuffer, int off, int v){ INT_VIEW.set(byteBuffer, off, v); }
	public static void writeInt5(byte[] byteBuffer, int off, long v){
		byteBuffer[off + 0] = (byte)(v >>> 0);
		byteBuffer[off + 1] = (byte)(v >>> 8);
		byteBuffer[off + 2] = (byte)(v >>> 16);
		byteBuffer[off + 3] = (byte)(v >>> 24);
		byteBuffer[off + 4] = (byte)(v >>> 32);
	}
	public static void writeInt6(byte[] byteBuffer, int off, long v){
		byteBuffer[off + 0] = (byte)(v >>> 0);
		byteBuffer[off + 1] = (byte)(v >>> 8);
		byteBuffer[off + 2] = (byte)(v >>> 16);
		byteBuffer[off + 3] = (byte)(v >>> 24);
		byteBuffer[off + 4] = (byte)(v >>> 32);
		byteBuffer[off + 5] = (byte)(v >>> 40);
	}
	public static void writeInt8(byte[] byteBuffer, int off, long v)    { LONG_VIEW.set(byteBuffer, off, v); }
	public static void writeFloat4(byte[] byteBuffer, int off, float v) { FLOAT_VIEW.set(byteBuffer, off, v); }
	public static void writeFloat8(byte[] byteBuffer, int off, double v){ DOUBLE_VIEW.set(byteBuffer, off, v); }
	
	//////////READ//////////
	
	public static char readChar2(byte[] byteBuffer, int offset)      { return (char)CHAR_VIEW.get(byteBuffer, offset); }
	public static short readInt2(byte[] byteBuffer, int offset)      { return (short)SHORT_VIEW.get(byteBuffer, offset); }
	public static int readUnsignedInt2(byte[] byteBuffer, int offset){ return readChar2(byteBuffer, offset); }
	public static int readUnsignedInt3(byte[] byteBuffer, int offset){
		return (((byteBuffer[offset + 0]&255)<<0) +
		        ((byteBuffer[offset + 1]&255)<<8) +
		        ((byteBuffer[offset + 2]&255)<<16));
	}
	public static int readInt4(byte[] byteBuffer, int offset){ return (int)INT_VIEW.get(byteBuffer, offset); }
	public static long readUnsignedInt5(byte[] byteBuffer, int offset){
		return (((long)(byteBuffer[offset + 0]&255)<<0) +
		        ((long)(byteBuffer[offset + 1]&255)<<8) +
		        ((long)(byteBuffer[offset + 2]&255)<<16) +
		        ((long)(byteBuffer[offset + 3]&255)<<24) +
		        ((long)(byteBuffer[offset + 4]&255)<<32));
	}
	public static long readUnsignedInt6(byte[] byteBuffer, int offset){
		return (((long)(byteBuffer[offset + 0]&255)<<0) +
		        ((long)(byteBuffer[offset + 1]&255)<<8) +
		        ((long)(byteBuffer[offset + 2]&255)<<16) +
		        ((long)(byteBuffer[offset + 3]&255)<<24) +
		        ((long)(byteBuffer[offset + 4]&255)<<32) +
		        ((long)(byteBuffer[offset + 5]&255)<<40));
	}
	public static long readInt8(byte[] byteBuffer, int offset) { return (long)LONG_VIEW.get(byteBuffer, offset); }
	public static float readFloat4(byte[] byteBuffer, int off) { return (float)FLOAT_VIEW.get(byteBuffer, off); }
	public static double readFloat8(byte[] byteBuffer, int off){ return (double)DOUBLE_VIEW.get(byteBuffer, off); }
}
