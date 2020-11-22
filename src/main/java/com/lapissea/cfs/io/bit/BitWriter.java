package com.lapissea.cfs.io.bit;

import java.io.IOException;

public interface BitWriter{
	
	BitWriter writeBits(long data, int bitCount) throws IOException;
	
	default BitWriter writeBoolBit(boolean bool) throws IOException{
		writeBits(bool?1:0, 1);
		return this;
	}
	
	default <T extends Enum<T>> BitWriter writeEnum(EnumUniverse<T> info, T val, boolean nullable) throws IOException{
		info.write(val, this, nullable);
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
