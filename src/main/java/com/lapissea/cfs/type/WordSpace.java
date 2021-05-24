package com.lapissea.cfs.type;

public enum WordSpace{
	BIT(1),
	BYTE(2);
	
	public final int sortOrder;
	WordSpace(int sortOrder){this.sortOrder=sortOrder;}
}
