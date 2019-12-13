package com.lapissea.fsf;

import java.io.IOException;

@SuppressWarnings("PointlessBitwiseExpression")
public interface ContentWriter{
	
	void write(int b) throws IOException;
	
	void write(byte[] b, int off, int len) throws IOException;
	
	default void writeBoolean(boolean v) throws IOException{
		write(v?1:0);
	}
	
	default void writeByte(int v) throws IOException{
		write(v);
	}
	
	default void writeShort(int v) throws IOException{
		write((v >>> 8)&0xFF);
		write((v >>> 0)&0xFF);
	}
	
	default void writeChar(int v) throws IOException{
		write((v >>> 8)&0xFF);
		write((v >>> 0)&0xFF);
	}
	
	default void writeInt(int v) throws IOException{
		write((v >>> 24)&0xFF);
		write((v >>> 16)&0xFF);
		write((v >>> 8)&0xFF);
		write((v >>> 0)&0xFF);
	}
	
	default void writeLong(long v) throws IOException{
		byte[] writeBuffer=new byte[8];
		writeBuffer[0]=(byte)(v >>> 56);
		writeBuffer[1]=(byte)(v >>> 48);
		writeBuffer[2]=(byte)(v >>> 40);
		writeBuffer[3]=(byte)(v >>> 32);
		writeBuffer[4]=(byte)(v >>> 24);
		writeBuffer[5]=(byte)(v >>> 16);
		writeBuffer[6]=(byte)(v >>> 8);
		writeBuffer[7]=(byte)(v >>> 0);
		write(writeBuffer, 0, writeBuffer.length);
	}
	
	default void writeFloat(float v) throws IOException{
		writeInt(Float.floatToIntBits(v));
	}
	
	default void writeDouble(double v) throws IOException{
		writeLong(Double.doubleToLongBits(v));
	}
	
}
