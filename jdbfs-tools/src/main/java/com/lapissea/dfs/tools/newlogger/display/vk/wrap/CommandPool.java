package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.vk.CommandBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import com.lapissea.dfs.utils.iterableplus.Iters;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;

import java.util.List;

import static com.lapissea.dfs.tools.newlogger.display.VUtils.check;

public class CommandPool implements VulkanResource{
	
	public enum Type{
		/**For general use*/
		NORMAL,
		/**For data that is often rewritten*/
		SHORT_LIVED,
		/**For data that is set only once*/
		WRITE_ONCE
	}
	
	public final long   handle;
	public final Device device;
	public final Type   type;
	
	public CommandPool(long handle, Device device, Type type){
		this.handle = handle;
		this.device = device;
		this.type = type;
	}
	
	public List<CommandBuffer> createCommandBuffer(int count){
		try(var mem = MemoryStack.stackPush()){
			var info = VkCommandBufferAllocateInfo.calloc(mem)
			                                      .sType$Default()
			                                      .commandPool(handle)
			                                      .level(VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY)
			                                      .commandBufferCount(count);
			
			var bufferRefs = mem.mallocPointer(count);
			check(VK10.vkAllocateCommandBuffers(device.value, info, bufferRefs), "allocateCommandBuffers");
			
			return Iters.rangeMap(0, bufferRefs.capacity(), i -> new CommandBuffer(bufferRefs.get(i), this, device)).toList();
		}
	}
	
	@Override
	public void destroy(){
		VK10.vkDestroyCommandPool(device.value, handle, null);
	}
}
