package com.lapissea.cfs.exceptions;

import java.io.IOException;

public class MalformedFileException extends IOException{
	
	public MalformedFileException(){
	}
	
	public MalformedFileException(String message){
		super(message);
	}
	
	public MalformedFileException(String message, Throwable cause){
		super(message, cause);
	}
	
	public MalformedFileException(Throwable cause){
		super(cause);
	}
}
