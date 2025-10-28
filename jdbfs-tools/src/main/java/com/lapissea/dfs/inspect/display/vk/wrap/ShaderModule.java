package com.lapissea.dfs.inspect.display.vk.wrap;

import com.lapissea.dfs.objects.Stringify;
import com.lapissea.dfs.inspect.display.vk.VulkanResource;
import com.lapissea.dfs.inspect.display.vk.enums.VkShaderStageFlag;
import org.lwjgl.vulkan.VK10;

public class ShaderModule extends VulkanResource.DeviceHandleObj implements Stringify{
	
	public final VkShaderStageFlag stage;
	
	public String name;
	
	public ShaderModule(Device device, long handle, VkShaderStageFlag stage){
		super(device, handle);
		this.stage = stage;
	}
	
	@Override
	public String toString(){
		return "ShaderModule" + toShortString();
	}
	@Override
	public String toShortString(){
		return "{" + (name == null? "handle: 0x" + Long.toHexString(handle) : "name: " + name) + ", stage: " + stage + "}";
	}
	@Override
	public void destroy(){
		VK10.vkDestroyShaderModule(device.value, handle, null);
	}
}
