package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.MappedVkMemory;
import com.lapissea.dfs.tools.newlogger.display.vk.VKCalls;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import org.lwjgl.vulkan.VK10;

public class VkDeviceMemory implements VulkanResource{
	
	public final long   handle;
	public final Device device;
	public final long   allocationSize;
	
	public VkDeviceMemory(long handle, Device device, long allocationSize){
		this.handle = handle;
		this.device = device;
		this.allocationSize = allocationSize;
	}
	
	public MappedVkMemory map() throws VulkanCodeException{
		return VKCalls.vkMapMemory(this, 0, allocationSize, 0);
	}
	
	@Override
	public void destroy(){
		VK10.vkFreeMemory(device.value, handle, null);
	}
}
