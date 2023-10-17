package com.lapissea.dfs.exceptions;

import java.io.IOException;
import java.io.Serial;

public class MalformedPointer extends IOException{
	
	@Serial
	private static final long serialVersionUID = 6669766626830188682L;
	public MalformedPointer(){
	}
	
	public MalformedPointer(String message){
		super(message);
	}
	
	public MalformedPointer(String message, Throwable cause){
		super(message, cause);
	}
	
	public MalformedPointer(Throwable cause){
		super(cause);
	}
}
