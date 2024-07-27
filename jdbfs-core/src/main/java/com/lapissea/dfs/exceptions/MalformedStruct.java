package com.lapissea.dfs.exceptions;

import com.lapissea.dfs.logging.Log;

public class MalformedStruct extends RuntimeException{
	
	public MalformedStruct(){
	}
	
	public MalformedStruct(String fmt, Throwable e, String message, Object... args){
		super(switch(fmt){
			case "fmt" -> Log.fmt(message, args);
			default -> throw new IllegalStateException();
		}, e);
	}
	public MalformedStruct(String fmt, String message, Object... args){
		super(switch(fmt){
			case "fmt" -> Log.fmt(message, args);
			default -> throw new IllegalStateException();
		});
	}
	
	public MalformedStruct(String message){
		super(message);
	}
	
	public MalformedStruct(String message, Throwable cause){
		super(message, cause);
	}
	
	public MalformedStruct(Throwable cause){
		super(cause);
	}
}
