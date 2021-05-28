package com.lapissea.cfs.io.bit;

import com.lapissea.util.NotNull;

import java.io.IOException;

public interface BitWriter<SELF extends BitWriter<SELF>>{
	
	SELF writeBits(long data, int bitCount) throws IOException;
	
	@SuppressWarnings("unchecked")
	private SELF self(){
		return (SELF)this;
	}
	
	default SELF writeBoolBit(boolean bool) throws IOException{
		writeBits(bool?1:0, 1);
		return self();
	}
	
	default <T extends Enum<T>> SELF writeEnum(@NotNull T val, boolean nullable) throws IOException{
		return writeEnum(EnumUniverse.getUnknown(val.getClass()), val, nullable);
	}
	
	default <T extends Enum<T>> SELF writeEnum(EnumUniverse<T> info, T val, boolean nullable) throws IOException{
		info.write(val, this, nullable);
		return self();
	}
	
	
	default SELF writeBits(int data, int bitCount) throws IOException{
		writeBits((long)data, bitCount);
		return self();
	}
	
	default SELF writeBits(boolean[] data) throws IOException{
		for(boolean f : data){
			writeBoolBit(f);
		}
		return self();
	}
	
	
	default SELF fillNOne(int n) throws IOException{
		for(int i=0;i<n;i++){
			writeBoolBit(true);
		}
		return self();
	}
	
}
