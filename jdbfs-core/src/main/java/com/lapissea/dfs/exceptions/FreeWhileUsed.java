package com.lapissea.dfs.exceptions;

import java.io.IOException;

public class FreeWhileUsed extends IOException{
	
	public FreeWhileUsed(){
	
	}
	
	public FreeWhileUsed(String message){
		super(message);
	}
	
	public FreeWhileUsed(String message, Throwable cause){
		super(message, cause);
	}
	
	public FreeWhileUsed(Throwable cause){
		super(cause);
	}
}
