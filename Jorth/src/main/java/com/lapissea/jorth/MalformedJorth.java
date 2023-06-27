package com.lapissea.jorth;

public class MalformedJorth extends Exception{
	
	public MalformedJorth(){
	}
	public MalformedJorth(String message){
		super(message);
	}
	public MalformedJorth(String message, Throwable cause){
		super(message, cause);
	}
	public MalformedJorth(Throwable cause){
		super(cause);
	}
}
