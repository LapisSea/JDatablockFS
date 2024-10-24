package com.lapissea.dfs.exceptions;

public class MissingConstructor extends MalformedStruct{
	
	public MissingConstructor(){
	}
	public MissingConstructor(String fmt, Throwable e, String message, Object... args){
		super(fmt, e, message, args);
	}
	public MissingConstructor(String fmt, String message, Object... args){
		super(fmt, message, args);
	}
	public MissingConstructor(String message){
		super(message);
	}
	
	public MissingConstructor(String message, Throwable cause){
		super(message, cause);
	}
	
	public MissingConstructor(Throwable cause){
		super(cause);
	}
}
