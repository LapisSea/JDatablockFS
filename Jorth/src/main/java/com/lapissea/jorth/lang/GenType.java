package com.lapissea.jorth.lang;

import java.util.Arrays;
import java.util.List;

public class GenType{
	
	public final String        typeName;
	public final Types         type;
	public final List<GenType> args;
	
	public GenType(String typeName, List<GenType> args){
		this.typeName=typeName;
		this.args=args;
		
		var lower=typeName.toLowerCase();
		type=Arrays.stream(Types.values()).filter(e->e.lower.equals(lower)).findAny().orElse(Types.OBJECT);
	}
}
