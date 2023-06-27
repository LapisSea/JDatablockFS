package com.lapissea.cfs.exceptions;

public class IllegalField extends MalformedStruct{
	
	public IllegalField(){
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
