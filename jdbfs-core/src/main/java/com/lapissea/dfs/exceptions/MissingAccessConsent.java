package com.lapissea.dfs.exceptions;

import com.lapissea.dfs.logging.Log;

public class MissingAccessConsent extends RuntimeException{
	
	public MissingAccessConsent(){
	}
	
	public MissingAccessConsent(String fmt, Throwable e, String message, Object... args){
		super(switch(fmt){
			case "fmt" -> Log.fmt(message, args);
			default -> throw new IllegalStateException();
		}, e);
	}
	public MissingAccessConsent(String fmt, String message, Object arg1){
		this(fmt, message, new Object[]{arg1});
	}
	public MissingAccessConsent(String fmt, String message, Object arg1, Object arg2){
		this(fmt, message, new Object[]{arg1, arg2});
	}
	public MissingAccessConsent(String fmt, String message, Object arg1, Object arg2, Object arg3){
		this(fmt, message, new Object[]{arg1, arg2, arg3});
	}
	public MissingAccessConsent(String fmt, String message, Object... args){
		super(switch(fmt){
			case "fmt" -> Log.fmt(message, args);
			default -> throw new IllegalStateException();
		});
	}
	
	public MissingAccessConsent(String message){
		super(message);
	}
	
	public MissingAccessConsent(String message, Throwable cause){
		super(message, cause);
	}
	
	public MissingAccessConsent(Throwable cause){
		super(cause);
	}
	
	
}
