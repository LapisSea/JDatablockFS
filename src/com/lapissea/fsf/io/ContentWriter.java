package com.lapissea.fsf.io;

import java.io.IOException;

@SuppressWarnings("PointlessBitwiseExpression")
public interface ContentWriter{
	
	byte[] contentBuf();
	
	default void writeChar(int v) throws IOException{
		byte[] writeBuffer=contentBuf();
		writeBuffer[0]=(byte)((v >>> 8)&0xFF);
		writeBuffer[1]=(byte)((v >>> 0)&0xFF);
		write(writeBuffer, 0, 2);
	}
	
	void write(int b) throws IOException;
	
	default void write(byte[] b) throws IOException{
		write(b, 0, b.length);
	}
	
	void write(byte[] b, int off, int len) throws IOException;
	
	default void writeBoolean(boolean v) throws IOException{
		writeInt1(v?1:0);
	}
	
	default void writeInt1(int v) throws IOException{
		write(v);
	}
	
	default void writeInt2(int v) throws IOException{
		byte[] writeBuffer=contentBuf();
		writeBuffer[0]=(byte)((v >>> 8)&0xFF);
		writeBuffer[1]=(byte)((v >>> 0)&0xFF);
		write(writeBuffer, 0, 2);
		
	}
	
	default void writeInt3(int v) throws IOException{
		byte[] writeBuffer=contentBuf();
		writeBuffer[0]=(byte)((v >>> 16)&0xFF);
		writeBuffer[1]=(byte)((v >>> 8)&0xFF);
		writeBuffer[2]=(byte)((v >>> 0)&0xFF);
		write(writeBuffer, 0, 3);
	}
	
	default void writeInt4(int v) throws IOException{
		byte[] writeBuffer=contentBuf();
		writeBuffer[0]=(byte)((v >>> 24)&0xFF);
		writeBuffer[1]=(byte)((v >>> 16)&0xFF);
		writeBuffer[2]=(byte)((v >>> 8)&0xFF);
		writeBuffer[3]=(byte)((v >>> 0)&0xFF);
		write(writeBuffer, 0, 4);
	}
	
	default void writeInt5(long v) throws IOException{
		byte[] writeBuffer=contentBuf();
		writeBuffer[0]=(byte)(v >>> 32);
		writeBuffer[1]=(byte)(v >>> 24);
		writeBuffer[2]=(byte)(v >>> 16);
		writeBuffer[3]=(byte)(v >>> 8);
		writeBuffer[4]=(byte)(v >>> 0);
		write(writeBuffer, 0, 5);
	}
	
	default void writeInt6(long v) throws IOException{
		byte[] writeBuffer=contentBuf();
		writeBuffer[0]=(byte)(v >>> 40);
		writeBuffer[1]=(byte)(v >>> 32);
		writeBuffer[2]=(byte)(v >>> 24);
		writeBuffer[3]=(byte)(v >>> 16);
		writeBuffer[4]=(byte)(v >>> 8);
		writeBuffer[5]=(byte)(v >>> 0);
		write(writeBuffer, 0, 6);
	}
	
	default void writeInt8(long v) throws IOException{
		byte[] writeBuffer=contentBuf();
		writeBuffer[0]=(byte)(v >>> 56);
		writeBuffer[1]=(byte)(v >>> 48);
		writeBuffer[2]=(byte)(v >>> 40);
		writeBuffer[3]=(byte)(v >>> 32);
		writeBuffer[4]=(byte)(v >>> 24);
		writeBuffer[5]=(byte)(v >>> 16);
		writeBuffer[6]=(byte)(v >>> 8);
		writeBuffer[7]=(byte)(v >>> 0);
		write(writeBuffer, 0, 8);
	}
	
	default void writeFloat4(float v) throws IOException{
		writeInt4(Float.floatToIntBits(v));
	}
	
	default void writeFloat8(double v) throws IOException{
		writeInt8(Double.doubleToLongBits(v));
	}
	
}
