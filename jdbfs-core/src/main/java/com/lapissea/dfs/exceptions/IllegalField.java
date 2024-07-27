package com.lapissea.dfs.exceptions;

public class IllegalField extends MalformedStruct{
	
	public IllegalField(){
	}
	public IllegalField(String fmt, String message, Object... args){
		super(fmt, message, args);
	}
	public IllegalField(String message){
		super(message);
	}
	
	public IllegalField(String message, Throwable cause){
		super(message, cause);
	}
	
	public IllegalField(Throwable cause){
		super(cause);
	}
}
