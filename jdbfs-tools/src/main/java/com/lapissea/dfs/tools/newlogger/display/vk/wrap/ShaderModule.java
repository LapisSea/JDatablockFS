package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import org.lwjgl.vulkan.VK10;

public class ShaderModule implements VulkanResource{
	
	public final long   handle;
	public final Device device;
	
	public ShaderModule(long handle, Device device){
		this.handle = handle;
		this.device = device;
	}
	@Override
	public void destroy(){
		VK10.vkDestroyShaderModule(device.value, handle, null);
	}
}
