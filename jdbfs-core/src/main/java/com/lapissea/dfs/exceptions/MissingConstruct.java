package com.lapissea.dfs.exceptions;

public class MissingConstruct extends MalformedStruct{
	
	public MissingConstruct(){
	}
	
	public MissingConstruct(String fmt, String message, Object... args){
		super(fmt, message, args);
	}
	public MissingConstruct(String message){
		super(message);
	}
	
	public MissingConstruct(String message, Throwable cause){
		super(message, cause);
	}
	
	public MissingConstruct(Throwable cause){
		super(cause);
	}
}
