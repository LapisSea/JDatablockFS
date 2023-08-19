package com.lapissea.cfs.io.content;

@SuppressWarnings({"PointlessArithmeticExpression", "PointlessBitwiseExpression"})
public final class ContentSupport{
	public static void writeInt1(byte[] out, int off, byte value){
		out[off] = value;
	}
	
	public static void writeChar2(byte[] writeBuffer, int off, int v){
		writeBuffer[off + 0] = (byte)((v >>> 8)&0xFF);
		writeBuffer[off + 1] = (byte)((v >>> 0)&0xFF);
	}
	public static void writeInt2(byte[] writeBuffer, int off, int v){
		writeBuffer[off + 0] = (byte)((v >>> 8)&0xFF);
		writeBuffer[off + 1] = (byte)((v >>> 0)&0xFF);
	}
	public static void writeInt3(byte[] writeBuffer, int off, int v){
		writeBuffer[off + 0] = (byte)((v >>> 16)&0xFF);
		writeBuffer[off + 1] = (byte)((v >>> 8)&0xFF);
		writeBuffer[off + 2] = (byte)((v >>> 0)&0xFF);
	}
	public static void writeInt4(byte[] writeBuffer, int off, int v){
		writeBuffer[off + 0] = (byte)((v >>> 24)&0xFF);
		writeBuffer[off + 1] = (byte)((v >>> 16)&0xFF);
		writeBuffer[off + 2] = (byte)((v >>> 8)&0xFF);
		writeBuffer[off + 3] = (byte)((v >>> 0)&0xFF);
	}
	public static void writeInt5(byte[] writeBuffer, int off, long v){
		writeBuffer[off + 0] = (byte)(v >>> 32);
		writeBuffer[off + 1] = (byte)(v >>> 24);
		writeBuffer[off + 2] = (byte)(v >>> 16);
		writeBuffer[off + 3] = (byte)(v >>> 8);
		writeBuffer[off + 4] = (byte)(v >>> 0);
	}
	public static void writeInt6(byte[] writeBuffer, int off, long v){
		writeBuffer[off + 0] = (byte)(v >>> 40);
		writeBuffer[off + 1] = (byte)(v >>> 32);
		writeBuffer[off + 2] = (byte)(v >>> 24);
		writeBuffer[off + 3] = (byte)(v >>> 16);
		writeBuffer[off + 4] = (byte)(v >>> 8);
		writeBuffer[off + 5] = (byte)(v >>> 0);
	}
	public static void writeInt8(byte[] writeBuffer, int off, long v){
		writeBuffer[off + 0] = (byte)(v >>> 56);
		writeBuffer[off + 1] = (byte)(v >>> 48);
		writeBuffer[off + 2] = (byte)(v >>> 40);
		writeBuffer[off + 3] = (byte)(v >>> 32);
		writeBuffer[off + 4] = (byte)(v >>> 24);
		writeBuffer[off + 5] = (byte)(v >>> 16);
		writeBuffer[off + 6] = (byte)(v >>> 8);
		writeBuffer[off + 7] = (byte)(v >>> 0);
	}
	public static void writeFloat4(byte[] writeBuffer, int off, float v){
		writeInt4(writeBuffer, off, Float.floatToIntBits(v));
	}
	public static void writeFloat8(byte[] writeBuffer, int off, double v){
		writeInt8(writeBuffer, off, Double.doubleToLongBits(v));
	}
	
	
	public static char readChar2(byte[] readBuffer, int offset){
		return (char)((readBuffer[offset + 0]<<8) +
		              (readBuffer[offset + 1]<<0));
	}
	public static short readInt2(byte[] readBuffer, int offset){
		return (short)(((readBuffer[offset + 0]&255)<<8) +
		               ((readBuffer[offset + 1]&255)<<0));
	}
	public static int readUnsignedInt2(byte[] readBuffer, int offset){
		return ((readBuffer[offset + 0]&255)<<8) +
		       ((readBuffer[offset + 1]&255)<<0);
	}
	public static int readUnsignedInt3(byte[] readBuffer, int offset){
		return (((readBuffer[offset + 0]&255)<<16) +
		        ((readBuffer[offset + 1]&255)<<8) +
		        ((readBuffer[offset + 2]&255)<<0));
	}
	public static int readInt4(byte[] readBuffer, int offset){
		return (((readBuffer[offset + 0]&255)<<24) +
		        ((readBuffer[offset + 1]&255)<<16) +
		        ((readBuffer[offset + 2]&255)<<8) +
		        ((readBuffer[offset + 3]&255)<<0));
	}
	public static long readUnsignedInt5(byte[] readBuffer, int offset){
		return (((long)(readBuffer[offset + 0]&255)<<32) +
		        ((long)(readBuffer[offset + 1]&255)<<24) +
		        ((readBuffer[offset + 2]&255)<<16) +
		        ((readBuffer[offset + 3]&255)<<8) +
		        ((readBuffer[offset + 4]&255)<<0));
	}
	public static long readUnsignedInt6(byte[] readBuffer, int offset){
		return (((long)(readBuffer[offset + 0]&255)<<40) +
		        ((long)(readBuffer[offset + 1]&255)<<32) +
		        ((long)(readBuffer[offset + 2]&255)<<24) +
		        ((readBuffer[offset + 3]&255)<<16) +
		        ((readBuffer[offset + 4]&255)<<8) +
		        ((readBuffer[offset + 5]&255)<<0));
	}
	public static long readInt8(byte[] readBuffer, int offset){
		return (((long)readBuffer[offset + 0]<<56) +
		        ((long)(readBuffer[offset + 1]&255)<<48) +
		        ((long)(readBuffer[offset + 2]&255)<<40) +
		        ((long)(readBuffer[offset + 3]&255)<<32) +
		        ((long)(readBuffer[offset + 4]&255)<<24) +
		        ((readBuffer[offset + 5]&255)<<16) +
		        ((readBuffer[offset + 6]&255)<<8) +
		        ((readBuffer[offset + 7]&255)<<0));
	}
	public static float readFloat4(byte[] srcBuffer, int off){
		return Float.intBitsToFloat(readInt4(srcBuffer, off));
	}
	public static double readFloat8(byte[] srcBuffer, int off){
		return Double.longBitsToDouble(readInt8(srcBuffer, off));
	}
}
