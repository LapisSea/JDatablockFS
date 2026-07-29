package com.lapissea.jorth.exceptions;

public class IllegalConditionalMerge extends MalformedJorth{
	public IllegalConditionalMerge(){
	}
	public IllegalConditionalMerge(String message){
		super(message);
	}
	public IllegalConditionalMerge(String message, Throwable cause){
		super(message, cause);
	}
}
