package com.lapissea.cfs.exceptions;

import java.io.IOException;

public class UnknownAllocationMethodException extends IOException{
	
	public UnknownAllocationMethodException(){
	}
	public UnknownAllocationMethodException(String message){
		super(message);
	}
	public UnknownAllocationMethodException(String message, Throwable cause){
		super(message, cause);
	}
	public UnknownAllocationMethodException(Throwable cause){
		super(cause);
	}
}
