package com.lapissea.dfs.inspect.display.vk.wrap;

import com.lapissea.dfs.inspect.display.vk.VulkanResource;
import org.lwjgl.vulkan.VK10;

public class VkSampler extends VulkanResource.DeviceHandleObj{
	
	public VkSampler(Device device, long handle){ super(device, handle); }
	
	@Override
	public void destroy(){
		VK10.vkDestroySampler(device.value, handle, null);
	}
}
