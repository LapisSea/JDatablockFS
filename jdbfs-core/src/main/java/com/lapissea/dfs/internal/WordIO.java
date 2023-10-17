package com.lapissea.dfs.internal;

import com.lapissea.dfs.io.content.BBView;

import java.nio.ByteOrder;

public final class WordIO{
	private static final boolean IS_LITTLE_ENDIAN = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;
	
	public static long getWord(byte[] data, int off, int len){
		if(IS_LITTLE_ENDIAN) return getWordLE(data, off, len);
		return getWordLoopBE(data, off, len);
	}
	
	public static void setWord(long v, byte[] writeBuffer, int off, int len){
		if(IS_LITTLE_ENDIAN) setWordLE(v, writeBuffer, off, len);
		setWordLoopBE(v, writeBuffer, off, len);
	}
	
	private static long getWordLE(byte[] data, int off, int len){
		return switch(len){
			case 1 -> Byte.toUnsignedLong(data[off]);
			case 2 -> BBView.readUnsignedInt2(data, off);
			case 3 -> BBView.readUnsignedInt3(data, off);
			case 4 -> Integer.toUnsignedLong(BBView.readInt4(data, off));
			case 8 -> BBView.readInt8(data, off);
			default -> getWordLoopLE(data, off, len);
		};
	}
	
	private static void setWordLE(long v, byte[] writeBuffer, int off, int len){
		if(v == 0){
			for(int i = off, j = off + len; i<j; i++){
				writeBuffer[i] = 0;
			}
			return;
		}
		switch(len){
			case 1 -> writeBuffer[off] = (byte)v;
			case 2 -> BBView.writeInt2(writeBuffer, off, (short)v);
			case 3 -> BBView.writeInt3(writeBuffer, off, (int)v);
			case 4 -> BBView.writeInt4(writeBuffer, off, (int)v);
			case 8 -> BBView.writeInt8(writeBuffer, off, v);
			default -> setWordLoopLE(v, writeBuffer, off, len);
		}
	}
	
	private static long getWordLoopLE(byte[] data, int off, int len){
		long val = 0;
		for(int i = 0; i<len; i++){
			val |= (data[off + i]&255L)<<(i*8);
		}
		return val;
	}
	private static void setWordLoopLE(long v, byte[] writeBuffer, int off, int len){
		for(int i = 0; i<len; i++){
			writeBuffer[off + i] = (byte)(v >>> (i*8));
		}
	}
	
	private static long getWordLoopBE(byte[] data, int off, int len){
		long val = 0;
		for(int i = 0; i<len; i++){
			val |= (data[off + i]&255L)<<(i*8);
		}
		return val;
	}
	private static void setWordLoopBE(long v, byte[] writeBuffer, int off, int len){
		for(int i = 0; i<len; i++){
			writeBuffer[off + i] = (byte)(v >>> (i*8));
		}
	}
}
