package com.lapissea.jorth.lang;

import com.lapissea.jorth.lang.type.KeyedEnum;

public enum Keyword implements KeyedEnum{
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
	ARRAY_GET("array-get"),
	INC,
	CAST,
	EXTENDS,
	IMPLEMENTS,
	PERMITS,
	SUPER,
	NEW,
	CALL,
	IF,
	RETURN,
	THROW,
	DUP, POP,
	AT("@"),
	TYPE_ARG("type-arg"),
	CALL_VIRTUAL("call-virtual"),
	CALLING_FUNCTION("calling-fn"),
	BOOTSTRAP_FUNCTION("bootstrap-fn"),
	WHAT_THE_STACK("???");
	
	public static final Lookup<Keyword> LOOKUP = KeyedEnum.getLookup(Keyword.class);
	
	public final String key;
	
	Keyword(String customKey){
		this.key = customKey;
	}
	Keyword(){
		key = name().toLowerCase();
	}
	
	@Override
	public String key(){
		return key;
	}
}
