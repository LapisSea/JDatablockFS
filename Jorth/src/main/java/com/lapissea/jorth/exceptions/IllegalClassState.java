package com.lapissea.jorth.exceptions;

public class IllegalClassState extends RuntimeException{
	public IllegalClassState(){
	}
	public IllegalClassState(String message){
		super(message);
	}
	public IllegalClassState(String message, Throwable cause){
		super(message, cause);
	}
}
