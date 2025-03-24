package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkShaderStageFlag;
import org.lwjgl.vulkan.VK10;

import java.util.HashMap;
import java.util.Map;

public class ShaderModule extends VulkanResource.DeviceHandleObj{
	
	public final VkShaderStageFlag stage;
	
	public final Map<Integer, Object> specializationValues = new HashMap<>();
	
	public ShaderModule(Device device, long handle, VkShaderStageFlag stage){
		super(device, handle);
		this.stage = stage;
	}
	@Override
	public void destroy(){
		VK10.vkDestroyShaderModule(device.value, handle, null);
	}
}
