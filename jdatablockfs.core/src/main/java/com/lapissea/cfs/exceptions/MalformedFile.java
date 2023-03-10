package com.lapissea.cfs.exceptions;

import java.io.IOException;

public class MalformedFile extends IOException{
	
	public MalformedFile(){
	}
	
	public MalformedFile(String message){
		super(message);
	}
	
	public MalformedFile(String message, Throwable cause){
		super(message, cause);
	}
	
	public MalformedFile(Throwable cause){
		super(cause);
	}
}
