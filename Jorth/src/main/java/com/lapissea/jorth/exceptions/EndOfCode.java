package com.lapissea.jorth.exceptions;

public class EndOfCode extends MalformedJorth{
	
	public EndOfCode(){
	}
	public EndOfCode(String message){
		super(message);
	}
	public EndOfCode(String message, Throwable cause){
		super(message, cause);
	}
	public EndOfCode(Throwable cause){
		super(cause);
	}
}
