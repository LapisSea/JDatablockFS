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
		int maxBatch=64;
		for(int start=0;start<data.length;start+=maxBatch){
			var batchSize=Math.min(data.length-start, maxBatch);
			
			long batch=0;
			for(int i=0;i<batchSize;i++){
				batch|=(data[i+start]?1L:0L)<<i;
			}
			
			writeBits(batch, batchSize);
		}
		return self();
	}
	
	
	default SELF fillNOne(int n) throws IOException{
		int maxBatch=63;
		for(int start=0;start<n;start+=maxBatch){
			var batchSize=Math.min(n-start, maxBatch);
			
			writeBits(BitUtils.makeMask(batchSize), batchSize);
		}
		return self();
	}
	
}
