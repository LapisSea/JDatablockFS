package com.lapissea.cfs.io.bit;

public interface BitWriter{
	
	BitWriter writeBits(long data, int bitCount);
	
	default BitWriter writeBoolBit(boolean bool){
		writeBits(bool?1:0, 1);
		return this;
	}
	
	
	@SuppressWarnings("unchecked")
	default <T extends Enum<T>> BitWriter writeEnum(T val){
		return writeEnum(EnumFlag.get(val.getClass()), val);
	}
	
	default <T extends Enum<T>> BitWriter writeEnum(EnumFlag<T> info, T val){
		info.write(val, this);
		return this;
	}
	
	
	default BitWriter writeBits(int data, int bitCount){
		writeBits((long)data, bitCount);
		return this;
	}
	
	
	default BitWriter fillNOne(int n){
		for(int i=0;i<n;i++){
			writeBoolBit(true);
		}
		return this;
	}
	
}
