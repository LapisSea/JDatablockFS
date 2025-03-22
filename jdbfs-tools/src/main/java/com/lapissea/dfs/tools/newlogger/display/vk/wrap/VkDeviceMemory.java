package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.MappedVkMemory;
import com.lapissea.dfs.tools.newlogger.display.vk.VKCalls;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import org.lwjgl.vulkan.VK10;

public class VkDeviceMemory extends VulkanResource.DeviceHandleObj{
	
	public final long allocationSize;
	
	public VkDeviceMemory(Device device, long handle, long allocationSize){
		super(device, handle);
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
