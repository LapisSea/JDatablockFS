package com.lapissea.cfs.exceptions;

import java.io.Serial;

public class PointerOutsideFile extends MalformedPointer{
	
	@Serial
	private static final long serialVersionUID = 6669766626830188682L;
	public PointerOutsideFile(){
	}
	
	public PointerOutsideFile(String message){
		super(message);
	}
	
	public PointerOutsideFile(String message, Throwable cause){
		super(message, cause);
	}
	
	public PointerOutsideFile(Throwable cause){
		super(cause);
	}
}
