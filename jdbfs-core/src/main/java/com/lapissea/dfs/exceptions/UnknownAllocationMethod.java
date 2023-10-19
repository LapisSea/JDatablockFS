package com.lapissea.dfs.exceptions;

import java.io.IOException;

public class UnknownAllocationMethod extends IOException{
	
	public UnknownAllocationMethod(){
	}
	public UnknownAllocationMethod(String message){
		super(message);
	}
	public UnknownAllocationMethod(String message, Throwable cause){
		super(message, cause);
	}
	public UnknownAllocationMethod(Throwable cause){
		super(cause);
	}
}
