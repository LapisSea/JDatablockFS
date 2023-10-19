package com.lapissea.dfs.exceptions;

public class IllegalConfiguration extends RuntimeException{
	
	public IllegalConfiguration(){
	}
	
	public IllegalConfiguration(String message){
		super(message);
	}
	
	public IllegalConfiguration(String message, Throwable cause){
		super(message, cause);
	}
	
	public IllegalConfiguration(Throwable cause){
		super(cause);
	}
}
