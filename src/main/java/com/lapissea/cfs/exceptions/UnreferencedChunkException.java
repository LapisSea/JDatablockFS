package com.lapissea.cfs.exceptions;

public class UnreferencedChunkException extends MalformedClusterDataException{
	
	public UnreferencedChunkException(){
	}
	
	public UnreferencedChunkException(String message){
		super(message);
	}
	
	public UnreferencedChunkException(String message, Throwable cause){
		super(message, cause);
	}
	
	public UnreferencedChunkException(Throwable cause){
		super(cause);
	}
}
