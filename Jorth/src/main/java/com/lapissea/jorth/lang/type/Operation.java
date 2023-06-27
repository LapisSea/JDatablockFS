package com.lapissea.jorth.lang.type;

public enum Operation implements KeyedEnum{
	EQUALS("=="),
	NOT_EQUALS("!="),
	ADD("+"),
	SUB("-"),
	MUL("*"),
	DIV("/"),
	GREATER_THAN(">"),
	GREATER_THAN_OR_EQUAL(">="),
	LESS_THAN("<"),
	LESS_THAN_OR_EQUAL("<="),
	;
	
	private final String key;
	Operation(String key){ this.key = key; }
	Operation()          { this.key = name(); }
	
	
	@Override
	public String key(){
		return key;
	}
}
