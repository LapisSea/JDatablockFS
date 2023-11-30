package com.lapissea.dfs.exceptions;

public class IncompatibleVersionTransform extends RuntimeException{
	public IncompatibleVersionTransform(){
	}
	public IncompatibleVersionTransform(String message){
		super(message);
	}
	public IncompatibleVersionTransform(String message, Throwable cause){
		super(message, cause);
	}
	public IncompatibleVersionTransform(Throwable cause){
		super(cause);
	}
}
