package com.lapissea.dfs.tools.newlogger.display.vk;

import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkBuffer;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.function.Consumer;

public class UniformBuffer implements VulkanResource{
	
	private final List<BufferAndMemory> buffers;
	
	public UniformBuffer(List<BufferAndMemory> buffers){
		this.buffers = buffers;
	}
	
	public void update(int frameID, Consumer<ByteBuffer> uniformPopulator) throws VulkanCodeException{
		var buf = buffers.get(frameID);
		buf.update(uniformPopulator);
	}
	
	public VkBuffer getBuffer(int frame){
		return buffers.get(frame).buffer;
	}
	
	@Override
	public void destroy(){
		buffers.forEach(BufferAndMemory::destroy);
	}
}
