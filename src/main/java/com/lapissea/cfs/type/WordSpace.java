package com.lapissea.cfs.type;

public enum WordSpace{
	BIT(1, "bit"),
	BYTE(2, "byte");
	
	public final int    sortOrder;
	public final String friendlyName;
	WordSpace(int sortOrder, String friendlyName){
		this.sortOrder=sortOrder;
		this.friendlyName=friendlyName;
	}
}
