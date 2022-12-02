package com.lapissea.jorth.v2.lang;

import java.util.HashMap;
import java.util.Map;

public enum Keyword{
	DEFINE,
	AS,
	INTERFACE,
	CLASS,
	ENUM,
	FIELD,
	FUNCTION,
	START,
	END,
	ARG,
	RETURNS,
	GET,
	SET,
	VISIBILITY,
	EXTENDS,
	IMPLEMENTS,
	;
	
	public static final Map<String, Keyword> MAP;
	
	static{
		var values=Keyword.values();
		var map   =HashMap.<String, Keyword>newHashMap(values.length);
		for(Keyword value : values){
			map.put(value.name().toLowerCase(), value);
		}
		MAP=Map.copyOf(map);
	}
	
}
