package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import org.lwjgl.vulkan.VK10;

public class VkPipelineLayout extends VulkanResource.DeviceHandleObj{
	
	public VkPipelineLayout(Device device, long handle){ super(device, handle); }
	
	@Override
	public void destroy(){
		VK10.vkDestroyPipelineLayout(device.value, handle, null);
	}
}
