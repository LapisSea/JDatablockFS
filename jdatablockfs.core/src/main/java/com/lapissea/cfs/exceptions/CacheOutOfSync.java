package com.lapissea.cfs.exceptions;

import com.lapissea.util.TextUtil;

import java.io.IOException;
import java.util.Objects;

public class CacheOutOfSync extends IOException{
	
	public CacheOutOfSync(){
	
	}
	
	private static <T> String makeMsg(T cached, T actual){
		assert !Objects.equals(cached, actual);
		if(cached == null){
			return "cached missing, actual: " + actual;
		}
		if(actual == null){
			return "actual missing, cached: " + cached;
		}
		return "\n" + TextUtil.toTable("cached/actual", cached, actual);
	}
	
	public <T> CacheOutOfSync(T cached, T actual){
		this("Cache desync: " + makeMsg(cached, actual));
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
