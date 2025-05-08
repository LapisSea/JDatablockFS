package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkShaderStageFlag;
import org.lwjgl.vulkan.VK10;

public class ShaderModule extends VulkanResource.DeviceHandleObj{
	
	public final VkShaderStageFlag stage;
	
	public ShaderModule(Device device, long handle, VkShaderStageFlag stage){
		super(device, handle);
		this.stage = stage;
	}
	@Override
	public void destroy(){
		VK10.vkDestroyShaderModule(device.value, handle, null);
	}
}
