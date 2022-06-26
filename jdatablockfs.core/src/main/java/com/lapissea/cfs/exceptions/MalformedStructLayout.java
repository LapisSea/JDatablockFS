package com.lapissea.cfs.exceptions;

import java.io.Serial;

public class MalformedStructLayout extends RuntimeException{
	
	@Serial
	private static final long serialVersionUID=-7080003095967659414L;
	public MalformedStructLayout(){
	}
	
	public MalformedStructLayout(String message){
		super(message);
	}
	
	public MalformedStructLayout(String message, Throwable cause){
		super(message, cause);
	}
	
	public MalformedStructLayout(Throwable cause){
		super(cause);
	}
}
