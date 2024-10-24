package com.lapissea.dfs.exceptions;

public class MalformedPipe extends RuntimeException{
	
	public MalformedPipe(){
	}
	
	public MalformedPipe(String message){
		super(message);
	}
	
	public MalformedPipe(String message, Throwable cause){
		super(message, cause);
	}
	
	public MalformedPipe(Throwable cause){
		super(cause);
	}
}
