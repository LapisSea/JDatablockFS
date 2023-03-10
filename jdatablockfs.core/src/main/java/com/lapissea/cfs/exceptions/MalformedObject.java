package com.lapissea.cfs.exceptions;

import java.io.IOException;

public class MalformedObject extends IOException{
	
	public MalformedObject(){
	}
	
	public MalformedObject(String message){
		super(message);
	}
	
	public MalformedObject(String message, Throwable cause){
		super(message, cause);
	}
	
	public MalformedObject(Throwable cause){
		super(cause);
	}
}
