package com.lapissea.cfs.type;

import com.lapissea.cfs.io.bit.BitUtils;

import java.util.Arrays;

public enum WordSpace{
	BIT(1, "bit"),
	BYTE(2, "byte");
	
	public static long mapSize(WordSpace sourceSpace, WordSpace targetSpace, long val){
		return switch(sourceSpace){
			case BIT -> switch(targetSpace){
				case BIT -> val;
				case BYTE -> BitUtils.bitsToBytes(val);
			};
			case BYTE -> switch(targetSpace){
				case BIT -> val*Byte.SIZE;
				case BYTE -> val;
			};
		};
	}
	
	
	public static final WordSpace MIN = Arrays.stream(WordSpace.values()).reduce(WordSpace::min).orElseThrow();
	public static final WordSpace MAX = Arrays.stream(WordSpace.values()).reduce(WordSpace::max).orElseThrow();
	
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
