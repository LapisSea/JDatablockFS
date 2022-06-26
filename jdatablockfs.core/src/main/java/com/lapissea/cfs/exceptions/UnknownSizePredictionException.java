package com.lapissea.cfs.exceptions;

public class UnknownSizePredictionException extends RuntimeException{
	
	public UnknownSizePredictionException(){
	}
	public UnknownSizePredictionException(String message){
		super(message);
	}
	public UnknownSizePredictionException(String message, Throwable cause){
		super(message, cause);
	}
	public UnknownSizePredictionException(Throwable cause){
		super(cause);
	}
}
