package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import org.lwjgl.vulkan.VK10;

public class FrameBuffer extends VulkanResource.DeviceHandleObj{
	
	public FrameBuffer(Device device, long handle){ super(device, handle); }
	
	@Override
	public void destroy(){
		VK10.vkDestroyFramebuffer(device.value, handle, null);
	}
}
