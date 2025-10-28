package com.lapissea.dfs.inspect.display;

import com.lapissea.dfs.inspect.display.vk.enums.VkResult;

public class VulkanRecreateSwapchainException extends VulkanCodeException{
	
	public VulkanRecreateSwapchainException(String message, VkResult code){
		super(message, code);
	}
	
}
