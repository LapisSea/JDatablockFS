package com.lapissea.cfs.exceptions;

import java.io.IOException;

public class MalformedClusterDataException extends IOException{
	public MalformedClusterDataException(){
	}
	
	public MalformedClusterDataException(String message){
		super(message);
	}
	
	public MalformedClusterDataException(String message, Throwable cause){
		super(message, cause);
	}
	
	public MalformedClusterDataException(Throwable cause){
		super(cause);
	}
}
