package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.VKCalls;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkResult;
import org.lwjgl.vulkan.VK10;

public class VkFence implements VulkanResource{
	
	public final long   handle;
	public final Device device;
	
	public VkFence(long handle, Device device){
		this.handle = handle;
		this.device = device;
	}
	
	public void waitFor() throws VulkanCodeException{
		VKCalls.vkWaitForFence(device.value, this, Long.MAX_VALUE);
	}
	public void reset() throws VulkanCodeException{
		VKCalls.vkResetFences(device.value, this);
	}
	
	public VkResult getStatus(){
		return VkResult.from(VK10.vkGetFenceStatus(device.value, handle));
	}
	
	@Override
	public void destroy(){
		VK10.vkDestroyFence(device.value, handle, null);
	}
}
