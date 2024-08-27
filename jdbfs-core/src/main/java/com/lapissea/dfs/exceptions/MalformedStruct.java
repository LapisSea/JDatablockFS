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
	public MalformedStruct(String fmt, String message, Object arg1){
		this(fmt, message, new Object[]{arg1});
	}
	public MalformedStruct(String fmt, String message, Object arg1, Object arg2){
		this(fmt, message, new Object[]{arg1, arg2});
	}
	public MalformedStruct(String fmt, String message, Object arg1, Object arg2, Object arg3){
		this(fmt, message, new Object[]{arg1, arg2, arg3});
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
