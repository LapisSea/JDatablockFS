package com.lapissea.cfs.exceptions;

public class InvalidMagicIDException extends MalformedFileException{
	
	public InvalidMagicIDException(){
	}
	
	public InvalidMagicIDException(String message){
		super(message);
	}
	
	public InvalidMagicIDException(String message, Throwable cause){
		super(message, cause);
	}
	
	public InvalidMagicIDException(Throwable cause){
		super(cause);
	}
}
