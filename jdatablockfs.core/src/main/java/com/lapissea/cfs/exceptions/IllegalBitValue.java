package com.lapissea.cfs.exceptions;

import java.io.IOException;

public class IllegalBitValue extends IOException{
	
	public final long bit;
	
	public IllegalBitValue(long bit){
		this(bit, "Illegal bit found at " + bit);
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
