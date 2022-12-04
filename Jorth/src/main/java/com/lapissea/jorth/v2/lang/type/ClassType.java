package com.lapissea.jorth.v2.lang.type;

import com.lapissea.jorth.v2.lang.Keyword;

public enum ClassType{
	CLASS, INTERFACE, ENUM;
	
	
	public static ClassType from(Keyword keyword){
		if(keyword==Keyword.INTERFACE) return INTERFACE;
		if(keyword==Keyword.CLASS) return CLASS;
		if(keyword==Keyword.ENUM) return ENUM;
		throw new IllegalArgumentException();
	}
}