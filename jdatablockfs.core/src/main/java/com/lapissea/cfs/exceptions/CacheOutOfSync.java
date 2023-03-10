package com.lapissea.cfs.exceptions;

import com.lapissea.util.TextUtil;

import java.io.IOException;

public class CacheOutOfSync extends IOException{
	
	public CacheOutOfSync(){
	
	}
	
	public <T> CacheOutOfSync(T cached, T actual){
		this("Cache desync\n" + TextUtil.toTable("cached/actual", cached, actual));
	}
	
	public CacheOutOfSync(String message){
		super(message);
	}
	
	public CacheOutOfSync(String message, Throwable cause){
		super(message, cause);
	}
	
	public CacheOutOfSync(Throwable cause){
		super(cause);
	}
}
