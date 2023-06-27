package com.lapissea.cfs.exceptions;

public class MissingConstruct extends MalformedStruct{
	
	public MissingConstruct(){
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
