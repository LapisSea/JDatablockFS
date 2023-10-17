package com.lapissea.dfs.exceptions;

public class InvalidGenericArgument extends IllegalArgumentException{
	public InvalidGenericArgument(){
	}
	public InvalidGenericArgument(String s){
		super(s);
	}
	public InvalidGenericArgument(String message, Throwable cause){
		super(message, cause);
	}
	public InvalidGenericArgument(Throwable cause){
		super(cause);
	}
}
