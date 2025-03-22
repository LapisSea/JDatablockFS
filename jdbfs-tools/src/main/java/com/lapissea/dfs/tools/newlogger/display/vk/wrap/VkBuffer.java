package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkMemoryRequirements;

public class VkBuffer implements VulkanResource{
	
	public final long   handle;
	public final long   size;
	public final Device device;
	
	public VkBuffer(long handle, long size, Device device){
		this.handle = handle;
		this.size = size;
		this.device = device;
	}
	
	public MemoryRequirements getRequirements(){
		try(var stack = MemoryStack.stackPush()){
			var res = VkMemoryRequirements.malloc(stack);
			VK10.vkGetBufferMemoryRequirements(device.value, handle, res);
			return new MemoryRequirements(res.size(), res.alignment(), res.memoryTypeBits());
		}
	}
	
	@Override
	public void destroy(){
		VK10.vkDestroyBuffer(device.value, handle, null);
	}
}
