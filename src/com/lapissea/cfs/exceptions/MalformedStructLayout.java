package com.lapissea.cfs.exceptions;

public class MalformedStructLayout extends RuntimeException{
	
	public MalformedStructLayout(){
	}
	
	public MalformedStructLayout(String message){
		super(message);
	}
	
	public MalformedStructLayout(String message, Throwable cause){
		super(message, cause);
	}
	
	public MalformedStructLayout(Throwable cause){
		super(cause);
	}
}
