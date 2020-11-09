package com.lapissea.cfs.exceptions;

public class UnknownIOTypeException extends RuntimeException{
	
	private static final long serialVersionUID=-2004357288321606262L;
	
	public UnknownIOTypeException(){
	}
	
	public UnknownIOTypeException(String message){
		super(message);
	}
	
	public UnknownIOTypeException(String message, Throwable cause){
		super(message, cause);
	}
	
	public UnknownIOTypeException(Throwable cause){
		super(cause);
	}
}
