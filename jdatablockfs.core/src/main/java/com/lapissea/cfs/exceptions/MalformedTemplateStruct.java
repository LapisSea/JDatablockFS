package com.lapissea.cfs.exceptions;

public class MalformedTemplateStruct extends MalformedStruct{
	
	public MalformedTemplateStruct(){
	}
	
	public MalformedTemplateStruct(String message){
		super(message);
	}
	
	public MalformedTemplateStruct(String message, Throwable cause){
		super(message, cause);
	}
	
	public MalformedTemplateStruct(Throwable cause){
		super(cause);
	}
}
