package com.lapissea.dfs.exceptions;

public class MissingRoot extends MalformedFile{
	
	public MissingRoot(){
	}
	
	public MissingRoot(String message){
		super(message);
	}
	
	public MissingRoot(String message, Throwable cause){
		super(message, cause);
	}
	
	public MissingRoot(Throwable cause){
		super(cause);
	}
}
