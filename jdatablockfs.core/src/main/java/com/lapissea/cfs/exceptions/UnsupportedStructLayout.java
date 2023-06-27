package com.lapissea.cfs.exceptions;

public class UnsupportedStructLayout extends MalformedStruct{
	
	public UnsupportedStructLayout(){
	}
	
	public UnsupportedStructLayout(String message){
		super(message);
	}
	
	public UnsupportedStructLayout(String message, Throwable cause){
		super(message, cause);
	}
	
	public UnsupportedStructLayout(Throwable cause){
		super(cause);
	}
}
