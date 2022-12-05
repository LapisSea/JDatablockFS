package com.lapissea.jorth.v2.lang;

import java.util.HashMap;
import java.util.Map;

public enum Keyword{
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
	ACCESS,
	EXTENDS,
	IMPLEMENTS,
	SUPER,
	NEW,
	CALL,
	EQUALS,
	IF,
	RETURN,
	THROW,
	WHAT_THE_STACK("???");
	
	public final String key;
	
	public static final Map<String, Keyword> MAP;
	
	Keyword(String customKey){
		this.key = customKey;
	}
	Keyword(){
		key = name();
	}
	
	static{
		var values = Keyword.values();
		var map    = HashMap.<String, Keyword>newHashMap(values.length);
		for(Keyword value : values){
			map.put(value.key.toLowerCase(), value);
		}
		MAP = Map.copyOf(map);
	}
	
}
