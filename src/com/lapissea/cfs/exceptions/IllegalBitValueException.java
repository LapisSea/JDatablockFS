package com.lapissea.cfs.exceptions;

public class IllegalBitValueException extends RuntimeException{
	
	public IllegalBitValueException(){
	}
	public IllegalBitValueException(String message){
		super(message);
	}
	public IllegalBitValueException(String message, Throwable cause){
		super(message, cause);
	}
	public IllegalBitValueException(Throwable cause){
		super(cause);
	}
	public IllegalBitValueException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace){
		super(message, cause, enableSuppression, writableStackTrace);
	}
}
