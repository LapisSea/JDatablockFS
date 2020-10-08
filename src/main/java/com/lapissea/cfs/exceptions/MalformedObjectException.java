package com.lapissea.cfs.exceptions;

import java.io.IOException;

public class MalformedObjectException extends IOException{
	
	public MalformedObjectException(){
	}
	
	public MalformedObjectException(String message){
		super(message);
	}
	
	public MalformedObjectException(String message, Throwable cause){
		super(message, cause);
	}
	
	public MalformedObjectException(Throwable cause){
		super(cause);
	}
}
