package com.lapissea.cfs.exceptions;

public class IllegalAnnotation extends MalformedStruct{
	
	public IllegalAnnotation(){
	}
	
	public IllegalAnnotation(String message){
		super(message);
	}
	
	public IllegalAnnotation(String message, Throwable cause){
		super(message, cause);
	}
	
	public IllegalAnnotation(Throwable cause){
		super(cause);
	}
}
