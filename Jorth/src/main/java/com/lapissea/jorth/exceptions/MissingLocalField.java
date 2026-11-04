package com.lapissea.jorth.exceptions;

public class MissingLocalField extends MalformedJorth{
	public MissingLocalField(){
	}
	public MissingLocalField(String message){
		super(message);
	}
	public MissingLocalField(String message, Throwable cause){
		super(message, cause);
	}
	public MissingLocalField(Throwable cause){
		super(cause);
	}
}
