package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.CommandBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.VKCalls;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import com.lapissea.dfs.utils.iterableplus.Iters;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;

import java.util.List;

public class CommandPool extends VulkanResource.DeviceHandleObj{
	
	public enum Type{
		/**For general use*/
		NORMAL,
		/**For data that is often rewritten*/
		SHORT_LIVED,
		/**For data that is set only once*/
		WRITE_ONCE
	}
	
	public final Type type;
	
	public CommandPool(Device device, long handle, Type type){
		super(device, handle);
		this.type = type;
	}
	
	public CommandBuffer createCommandBuffer() throws VulkanCodeException{
		return createCommandBuffers(1).getFirst();
	}
	public List<CommandBuffer> createCommandBuffers(int count) throws VulkanCodeException{
		try(var mem = MemoryStack.stackPush()){
			var info = VkCommandBufferAllocateInfo.calloc(mem)
			                                      .sType$Default()
			                                      .commandPool(handle)
			                                      .level(VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY)
			                                      .commandBufferCount(count);
			
			var bufferRefs = mem.mallocPointer(count);
			VKCalls.vkAllocateCommandBuffers(device, info, bufferRefs);
			
			return Iters.rangeMap(0, bufferRefs.capacity(), i -> new CommandBuffer(bufferRefs.get(i), this)).toList();
		}
	}
	
	@Override
	public void destroy(){
		VK10.vkDestroyCommandPool(device.value, handle, null);
	}
}
