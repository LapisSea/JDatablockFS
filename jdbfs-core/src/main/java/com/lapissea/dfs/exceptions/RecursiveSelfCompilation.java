package com.lapissea.dfs.exceptions;

public class RecursiveSelfCompilation extends MalformedStruct{
	
	public RecursiveSelfCompilation(){
	}
	
	public RecursiveSelfCompilation(String message){
		super(message);
	}
	
	public RecursiveSelfCompilation(String message, Throwable cause){
		super(message, cause);
	}
	
	public RecursiveSelfCompilation(Throwable cause){
		super(cause);
	}
}
