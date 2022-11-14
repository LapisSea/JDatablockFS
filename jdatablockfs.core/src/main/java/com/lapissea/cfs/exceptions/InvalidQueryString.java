package com.lapissea.cfs.exceptions;

public class InvalidQueryString extends MalformedStruct{
	
	public InvalidQueryString(){
	}
	
	public InvalidQueryString(String message){
		super(message);
	}
	
	public InvalidQueryString(String message, Throwable cause){
		super(message, cause);
	}
	
	public InvalidQueryString(Throwable cause){
		super(cause);
	}
}
