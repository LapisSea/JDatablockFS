package com.lapissea.jorth;

public final class SafeClass{
	
	private final int safeField;
	
	public SafeClass(){
		this(-1);
	}
	
	private SafeClass(int field){
		safeField = field;
	}
	
	public int getSafeField(){
		return safeField;
	}
}
