package com.lapissea.dfs.type;

import com.lapissea.dfs.io.bit.BitUtils;

public enum WordSpace{
	BIT(1, "bit"),
	BYTE(2, "byte");
	
	public static long mapSize(WordSpace sourceSpace, WordSpace targetSpace, long val){
		if(sourceSpace == WordSpace.BIT){
			if(targetSpace == WordSpace.BIT) return val;
			else return BitUtils.bitsToBytes(val);
		}else{
			if(targetSpace == WordSpace.BIT) return val*Byte.SIZE;
			else return val;
		}
	}
	
	public final int    sortOrder;
	public final String friendlyName;
	WordSpace(int sortOrder, String friendlyName){
		this.sortOrder = sortOrder;
		this.friendlyName = friendlyName;
	}
	
	public WordSpace min(WordSpace other){
		if(sortOrder<=other.sortOrder) return this;
		return other;
	}
	public WordSpace max(WordSpace other){
		if(sortOrder>=other.sortOrder) return this;
		return other;
	}
}
