package com.lapissea.jorth.lang.type;


public enum ClassType{
	CLASS(true, false),
	INTERFACE(false, false),
	ENUM(true, true),
	ANNOTATION(false, false);
	
	public final boolean canBeFinal, mustBeFinal;
	
	ClassType(boolean canBeFinal, boolean mustBeFinal){
		this.canBeFinal = canBeFinal;
		this.mustBeFinal = mustBeFinal;
	}
}
