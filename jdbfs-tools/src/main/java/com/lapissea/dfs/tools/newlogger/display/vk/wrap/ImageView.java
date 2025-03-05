package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import org.lwjgl.vulkan.VK10;

public class ImageView implements VulkanResource{
	
	private final long   handle;
	private final Device device;
	
	public ImageView(long handle, Device device){
		this.handle = handle;
		this.device = device;
	}
	
	@Override
	public void destroy(){
		VK10.vkDestroyImageView(device.value, handle, null);
	}
}
