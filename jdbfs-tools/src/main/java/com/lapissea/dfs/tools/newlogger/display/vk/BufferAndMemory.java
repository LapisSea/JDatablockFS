package com.lapissea.dfs.tools.newlogger.display.vk;

import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkDeviceMemory;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

public class BufferAndMemory implements VulkanResource{
	
	public final VkBuffer       buffer;
	public final VkDeviceMemory memory;
	
	public BufferAndMemory(VkBuffer buffer, VkDeviceMemory memory){
		this.buffer = buffer;
		this.memory = memory;
	}
	
	
	@Override
	public void destroy(){
		memory.destroy();
		buffer.destroy();
	}
	
	public void copyTo(TransferBuffers transferBuffers, BufferAndMemory vb) throws VulkanCodeException{
		transferBuffers.syncAction(copyBuffer -> {
			copyBuffer.copyBuffer(buffer, vb.buffer, buffer.size);
		});
	}
	
	public void update(Consumer<ByteBuffer> populator) throws VulkanCodeException{
		update(0, buffer.size, populator);
	}
	public void update(long offset, long size, Consumer<ByteBuffer> populator) throws VulkanCodeException{
		try(var mem = memory.map(offset, size)){
			mem.populate(populator);
		}
	}
}
