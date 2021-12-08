package com.lapissea.jorth;

public class MalformedJorthException extends Exception{
	
	public MalformedJorthException(){
	}
	public MalformedJorthException(String message){
		super(message);
	}
	public MalformedJorthException(String message, Throwable cause){
		super(message, cause);
	}
	public MalformedJorthException(Throwable cause){
		super(cause);
	}
}
