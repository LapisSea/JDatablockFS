package com.lapissea.cfs.exceptions;

public class MalformedToStringFormat extends MalformedStruct{
	
	public MalformedToStringFormat(){
	}
	
	public MalformedToStringFormat(String message){
		super(message);
	}
	
	public MalformedToStringFormat(String message, Throwable cause){
		super(message, cause);
	}
	
	public MalformedToStringFormat(Throwable cause){
		super(cause);
	}
}
