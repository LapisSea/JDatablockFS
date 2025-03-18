package com.lapissea.dfs.tools.newlogger.display.vk;

import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkCommandBufferUsageFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.DeviceMemory;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VulkanQueue;

public class BufferAndMemory implements VulkanResource{
	
	public final VkBuffer     buffer;
	public final DeviceMemory memory;
	public final long         allocationSize;
	
	
	public BufferAndMemory(VkBuffer buffer, DeviceMemory memory, long allocationSize){
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
}
