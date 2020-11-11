package com.lapissea.cfs.io.bit;

import java.io.IOException;

public interface BitWriter{
	
	BitWriter writeBits(long data, int bitCount) throws IOException;
	
	default BitWriter writeBoolBit(boolean bool) throws IOException{
		writeBits(bool?1:0, 1);
		return this;
	}
	
	
	@SuppressWarnings("unchecked")
	default <T extends Enum<T>> BitWriter writeEnum(T val) throws IOException{
		return writeEnum(EnumFlag.get(val.getClass()), val);
	}
	
	default <T extends Enum<T>> BitWriter writeEnum(EnumFlag<T> info, T val) throws IOException{
		info.write(val, this);
		return this;
	}
	
	
	default BitWriter writeBits(int data, int bitCount) throws IOException{
		writeBits((long)data, bitCount);
		return this;
	}
	
	default BitWriter writeBits(boolean[] data) throws IOException{
		for(boolean f : data){
			writeBoolBit(f);
		}
		return this;
	}
	
	
	default BitWriter fillNOne(int n) throws IOException{
		for(int i=0;i<n;i++){
			writeBoolBit(true);
		}
		return this;
	}
	
}
