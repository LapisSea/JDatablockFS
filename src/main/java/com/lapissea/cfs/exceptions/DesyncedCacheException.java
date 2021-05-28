package com.lapissea.cfs.exceptions;

import com.lapissea.util.TextUtil;

import java.io.IOException;
import java.io.Serial;

public class DesyncedCacheException extends IOException{
	
	@Serial
	private static final long serialVersionUID=6669766626830188682L;
	
	public DesyncedCacheException(){
	
	}
	
	public <T> DesyncedCacheException(T cached, T actual){
		this("Cache desync\n"+TextUtil.toTable("cached/actual", cached, actual));
	}
	
	public DesyncedCacheException(String message){
		super(message);
	}
	
	public DesyncedCacheException(String message, Throwable cause){
		super(message, cause);
	}
	
	public DesyncedCacheException(Throwable cause){
		super(cause);
	}
}
