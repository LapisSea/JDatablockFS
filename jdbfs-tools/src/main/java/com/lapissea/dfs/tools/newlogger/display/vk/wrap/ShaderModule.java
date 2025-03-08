package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkShaderStageFlag;
import org.lwjgl.vulkan.VK10;

public class ShaderModule implements VulkanResource{
	
	public final long              handle;
	public final VkShaderStageFlag stage;
	public final Device            device;
	
	public ShaderModule(long handle, VkShaderStageFlag stage, Device device){
		this.handle = handle;
		this.stage = stage;
		this.device = device;
	}
	@Override
	public void destroy(){
		VK10.vkDestroyShaderModule(device.value, handle, null);
	}
}
