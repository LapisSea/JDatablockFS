package com.lapissea.cfs.exceptions;

public class MalformedStruct extends RuntimeException{
	
	public MalformedStruct(){
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
