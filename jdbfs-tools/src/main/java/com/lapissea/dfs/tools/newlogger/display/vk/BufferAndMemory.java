package com.lapissea.dfs.tools.newlogger.display.vk;

import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkCommandBufferUsageFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkDeviceMemory;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VulkanQueue;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

public class BufferAndMemory implements VulkanResource{
	
	public final VkBuffer       buffer;
	public final VkDeviceMemory memory;
	public final long           allocationSize;
	
	
	public BufferAndMemory(VkBuffer buffer, VkDeviceMemory memory, long allocationSize){
		this.buffer = buffer;
		this.memory = memory;
		this.allocationSize = allocationSize;
	}
	
	
	@Override
	public void destroy(){
		memory.destroy();
		buffer.destroy();
	}
	
	public void copyTo(CommandBuffer copyBuffer, VulkanQueue queue, BufferAndMemory vb) throws VulkanCodeException{
		copyBuffer.begin(Flags.of(VkCommandBufferUsageFlag.ONE_TIME_SUBMIT_BIT));
		copyBuffer.copyBuffer(buffer, vb.buffer, buffer.size);
		copyBuffer.end();
		
		queue.submitNow(copyBuffer);
		queue.waitIdle();
	}
	
	public void update(Consumer<ByteBuffer> populator) throws VulkanCodeException{
		try(var mem = VKCalls.vkMapMemory(memory, 0, allocationSize, 0)){
			mem.populate(populator);
		}
	}
}
