package com.lapissea.dfs.tools.newlogger.display;

import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkResult;

public class VulkanRecreateSwapchainException extends VulkanCodeException{
	
	public VulkanRecreateSwapchainException(String message, VkResult code){
		super(message, code);
	}
	
}
