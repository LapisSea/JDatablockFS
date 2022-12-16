package com.lapissea.jorth;

import java.util.Arrays;
import java.util.Optional;

public enum BracketType{
	
	REGULAR('(', ')'),
	SQUIGGLY('{', '}'),
	SQUARE('[', ']'),
	ANGLE('<', '>'),
	;
	
	public final char open, close;
	public final String openStr, closeStr;
	
	private static final Optional<BracketType>[] OPEN_LOOKUP;
	
	static{
		//noinspection unchecked
		OPEN_LOOKUP = new Optional[6];
		Arrays.fill(OPEN_LOOKUP, Optional.empty());
		for(BracketType value : values()){
			OPEN_LOOKUP[value.open%OPEN_LOOKUP.length] = Optional.of(value);
		}
	}
	
	BracketType(char open, char close){
		this.open = open;
		this.close = close;
		openStr = String.valueOf(open);
		closeStr = String.valueOf(close);
	}
	
	public static Optional<BracketType> byOpen(char value){
		return OPEN_LOOKUP[value%OPEN_LOOKUP.length].filter(c -> c.open == value);
	}
}
