package com.lapissea.dfs.internal;

import com.lapissea.dfs.io.content.BBView;

public final class WordIO{
	
	public static long getWord(byte[] data, int off, int len){
		return switch(len){
			case 0 -> 0;
			case 1 -> Byte.toUnsignedLong(data[off]);
			case 2 -> BBView.readUnsignedInt2(data, off);
			case 3 -> BBView.readUnsignedInt3(data, off);
			case 4 -> Integer.toUnsignedLong(BBView.readInt4(data, off));
			case 5 -> BBView.readUnsignedInt5(data, off);
			case 6 -> BBView.readUnsignedInt6(data, off);
			case 7 -> BBView.readUnsignedInt7(data, off);
			case 8 -> BBView.readInt8(data, off);
			default -> throw fail(len);
		};
	}
	
	public static void setWord(long v, byte[] writeBuffer, int off, int len){
		switch(len){
			case 0 -> { }
			case 1 -> writeBuffer[off] = (byte)v;
			case 2 -> BBView.writeInt2(writeBuffer, off, (short)v);
			case 3 -> BBView.writeInt3(writeBuffer, off, (int)v);
			case 4 -> BBView.writeInt4(writeBuffer, off, (int)v);
			case 5 -> BBView.writeInt5(writeBuffer, off, v);
			case 6 -> BBView.writeInt6(writeBuffer, off, v);
			case 7 -> BBView.writeInt7(writeBuffer, off, v);
			case 8 -> BBView.writeInt8(writeBuffer, off, v);
			default -> throw fail(len);
		}
	}
	
	private static RuntimeException fail(int len){
		throw new IllegalArgumentException("The length of a word must be in the range of 0-8" + len);
	}
}
