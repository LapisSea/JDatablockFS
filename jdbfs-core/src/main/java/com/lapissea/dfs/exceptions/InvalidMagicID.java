package com.lapissea.dfs.exceptions;

public class InvalidMagicID extends MalformedFile{
	
	public InvalidMagicID(){
	}
	
	public InvalidMagicID(String message){
		super(message);
	}
	
	public InvalidMagicID(String message, Throwable cause){
		super(message, cause);
	}
	
	public InvalidMagicID(Throwable cause){
		super(cause);
	}
}
