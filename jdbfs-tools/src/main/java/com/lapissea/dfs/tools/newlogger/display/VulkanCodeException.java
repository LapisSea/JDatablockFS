package com.lapissea.dfs.tools.newlogger.display;

import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkResult;

import java.util.Objects;

public class VulkanCodeException extends Exception{
	
	public static class Timeout extends VulkanCodeException{
		public Timeout(String message){
			super(message, VkResult.TIMEOUT);
		}
	}
	
	public static class OutOfPoolMemory extends VulkanCodeException{
		public OutOfPoolMemory(String message){
			super(message, VkResult.ERROR_OUT_OF_POOL_MEMORY);
		}
	}
	
	public static VulkanCodeException from(int errorCode, String action){
		var res = VkResult.from(errorCode);
		return switch(res){
			case SUBOPTIMAL_KHR, ERROR_OUT_OF_DATE_KHR -> new VulkanRecreateSwapchainException(action + ": " + res, res);
			case TIMEOUT -> new Timeout(action);
			case ERROR_OUT_OF_POOL_MEMORY -> new OutOfPoolMemory(action);
			default -> new VulkanCodeException(Log.fmt("Failed to call {}#red: {}#red", action, res), res);
		};
	}
	
	public final VkResult code;
	
	public VulkanCodeException(String message, VkResult code){
		super(message);
		this.code = Objects.requireNonNull(code);
	}
	
}
