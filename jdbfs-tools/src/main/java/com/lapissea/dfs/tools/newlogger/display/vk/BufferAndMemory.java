package com.lapissea.dfs.tools.newlogger.display.vk;

import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkDeviceMemory;
import com.lapissea.util.function.UnsafeConsumer;

import java.nio.ByteBuffer;

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
	
	public <E extends Throwable> void update(UnsafeConsumer<ByteBuffer, E> populator) throws E, VulkanCodeException{
		update(0, buffer.size, populator);
	}
	public <E extends Throwable> void update(long offset, long size, UnsafeConsumer<ByteBuffer, E> populator) throws E, VulkanCodeException{
		try(var mem = memory.map(offset, size)){
			mem.populate(populator);
		}
	}
	
	public long size(){ return buffer.size; }
}
