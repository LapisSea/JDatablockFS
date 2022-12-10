package com.lapissea.jorth.lang;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
	ACCESS,
	EXTENDS,
	IMPLEMENTS,
	SUPER,
	NEW,
	CALL,
	EQUALS("=="),
	IF,
	RETURN,
	THROW,
	DUP, POP,
	AT("@"),
	WHAT_THE_STACK("???");
	
	public final String key;
	
	public static final Map<String, Keyword> MAP;
	public static final char[]               SMOL_KEYS;
	public static final Keyword[]            SMOL_VALUES;
	
	Keyword(String customKey){
		this.key = customKey;
	}
	Keyword(){
		key = name().toLowerCase();
	}
	
	static{
		var values = values();
		var map    = HashMap.<String, Keyword>newHashMap(values.length);
		for(Keyword value : values){
			map.put(value.key, value);
		}
		MAP = Map.copyOf(map);
		
		List<Character> skeys = new ArrayList<>();
		List<Keyword>   svals = new ArrayList<>();
		
		for(Keyword value : values){
			if(value.key.length() == 1){
				skeys.add(value.key.charAt(0));
				svals.add(value);
			}
		}
		
		SMOL_KEYS = new char[skeys.size()];
		for(int i = 0; i<SMOL_KEYS.length; i++){
			SMOL_KEYS[i] = skeys.get(i);
		}
		SMOL_VALUES = svals.toArray(Keyword[]::new);
	}
	
}
