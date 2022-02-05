package com.lapissea.cfs.exceptions;

public class IllegalBitValueException extends RuntimeException{
	
	public final long bit;
	
	public IllegalBitValueException(long bit){
		this.bit=bit;
	}
	public IllegalBitValueException(long bit, String message){
		super(message);
		this.bit=bit;
	}
	public IllegalBitValueException(long bit, String message, Throwable cause){
		super(message, cause);
		this.bit=bit;
	}
	public IllegalBitValueException(long bit, Throwable cause){
		super(cause);
		this.bit=bit;
	}
}
