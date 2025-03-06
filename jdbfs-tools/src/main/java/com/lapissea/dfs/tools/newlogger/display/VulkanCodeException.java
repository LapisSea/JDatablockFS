package com.lapissea.dfs.tools.newlogger.display;

import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkResult;

import java.util.Objects;

public class VulkanCodeException extends IllegalStateException{
	
	public static VulkanCodeException from(int errorCode, String action){
		var res = VkResult.from(errorCode);
		return switch(res){
			case SUBOPTIMAL_KHR, ERROR_OUT_OF_DATE_KHR -> new VulkanRecreateSwapchainException(action, res);
			default -> throw new VulkanCodeException(Log.fmt("Failed to call {}#red: {}#red", action, res), res);
		};
	}
	
	public final VkResult code;
	
	public VulkanCodeException(String message, VkResult code){
		super(message);
		this.code = Objects.requireNonNull(code);
	}
	
}
