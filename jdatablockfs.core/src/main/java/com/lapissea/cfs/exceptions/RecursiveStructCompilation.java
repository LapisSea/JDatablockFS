package com.lapissea.cfs.exceptions;

public class RecursiveStructCompilation extends MalformedStruct{
	
	public RecursiveStructCompilation(){
	}
	
	public RecursiveStructCompilation(String message){
		super(message);
	}
	
	public RecursiveStructCompilation(String message, Throwable cause){
		super(message, cause);
	}
	
	public RecursiveStructCompilation(Throwable cause){
		super(cause);
	}
}
