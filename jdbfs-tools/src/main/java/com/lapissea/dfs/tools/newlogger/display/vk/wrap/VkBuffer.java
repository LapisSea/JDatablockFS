package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.Flags;
import com.lapissea.dfs.tools.newlogger.display.vk.VKCalls;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkMemoryPropertyFlag;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkMemoryRequirements;

public class VkBuffer extends VulkanResource.DeviceHandleObj{
	
	public final long size;
	
	public VkBuffer(Device device, long handle, long size){
		super(device, handle);
		this.size = size;
	}
	
	public VkDeviceMemory allocateAndBindRequiredMemory(PhysicalDevice physicalDevice, Flags<VkMemoryPropertyFlag> memoryFlags) throws VulkanCodeException{
		var requirement     = getRequirements();
		int memoryTypeIndex = physicalDevice.getMemoryTypeIndex(requirement.memoryTypeBits(), memoryFlags);
		var memory          = device.allocateMemory(requirement.size(), memoryTypeIndex);
		
		try{
			VKCalls.vkBindBufferMemory(this, memory, 0);
		}catch(Throwable e){
			memory.destroy();
			throw e;
		}
		return memory;
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
