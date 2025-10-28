package com.lapissea.dfs.inspect.display.vk.wrap;

import com.lapissea.dfs.inspect.display.vk.VulkanResource;
import org.lwjgl.vulkan.VK10;

public class VkImageView extends VulkanResource.DeviceHandleObj{
	
	public VkImageView(Device device, long handle){ super(device, handle); }
	
	@Override
	public void destroy(){
		VK10.vkDestroyImageView(device.value, handle, null);
	}
}
