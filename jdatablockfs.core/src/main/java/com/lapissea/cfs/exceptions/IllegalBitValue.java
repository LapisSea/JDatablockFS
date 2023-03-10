package com.lapissea.cfs.exceptions;

public class IllegalBitValue extends RuntimeException{
	
	public final long bit;
	
	public IllegalBitValue(long bit){
		this.bit = bit;
	}
	public IllegalBitValue(long bit, String message){
		super(message);
		this.bit = bit;
	}
	public IllegalBitValue(long bit, String message, Throwable cause){
		super(message, cause);
		this.bit = bit;
	}
	public IllegalBitValue(long bit, Throwable cause){
		super(cause);
		this.bit = bit;
	}
}
