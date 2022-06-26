package com.lapissea.cfs.exceptions;

import java.io.IOException;
import java.io.Serial;

public class MalformedPointerException extends IOException{
	
	@Serial
	private static final long serialVersionUID=6669766626830188682L;
	public MalformedPointerException(){
	}
	
	public MalformedPointerException(String message){
		super(message);
	}
	
	public MalformedPointerException(String message, Throwable cause){
		super(message, cause);
	}
	
	public MalformedPointerException(Throwable cause){
		super(cause);
	}
}
