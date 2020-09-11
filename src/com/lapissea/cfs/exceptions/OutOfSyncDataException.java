package com.lapissea.cfs.exceptions;

import java.io.IOException;

public class OutOfSyncDataException extends IOException{
	
	public OutOfSyncDataException(){
	}
	
	public OutOfSyncDataException(String message){
		super(message);
	}
	
	public OutOfSyncDataException(String message, Throwable cause){
		super(message, cause);
	}
	
	public OutOfSyncDataException(Throwable cause){
		super(cause);
	}
}
