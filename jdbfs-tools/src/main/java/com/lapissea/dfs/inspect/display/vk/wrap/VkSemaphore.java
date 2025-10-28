package com.lapissea.dfs.inspect.display.vk.wrap;

import com.lapissea.dfs.inspect.display.vk.VulkanResource;
import org.lwjgl.vulkan.VK10;

public class VkSemaphore extends VulkanResource.DeviceHandleObj{
	
	public VkSemaphore(Device device, long handle){ super(device, handle); }
	
	@Override
	public void destroy(){
		VK10.vkDestroySemaphore(device.value, handle, null);
	}
}
