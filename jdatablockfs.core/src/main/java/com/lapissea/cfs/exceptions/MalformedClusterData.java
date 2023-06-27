package com.lapissea.cfs.exceptions;

import java.io.IOException;

public class MalformedClusterData extends IOException{
	public MalformedClusterData(){
	}
	
	public MalformedClusterData(String message){
		super(message);
	}
	
	public MalformedClusterData(String message, Throwable cause){
		super(message, cause);
	}
	
	public MalformedClusterData(Throwable cause){
		super(cause);
	}
}
