package com.lapissea.dfs.tools.newlogger.display;

public class VulkanCodeException extends IllegalStateException{
	
	public final int code;
	
	public VulkanCodeException(String message, int code){
		super(message);
		this.code = code;
	}
	
}
