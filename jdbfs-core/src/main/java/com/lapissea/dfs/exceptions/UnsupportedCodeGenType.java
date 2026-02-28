package com.lapissea.dfs.exceptions;

public class UnsupportedCodeGenType extends Exception{
	
	public UnsupportedCodeGenType(){
	}
	
	public UnsupportedCodeGenType(String message){
		super(message);
	}
	
	public UnsupportedCodeGenType(String message, Throwable cause){
		super(message, cause);
	}
	
	public UnsupportedCodeGenType(Throwable cause){
		super(cause);
	}
}
