package com.lapissea.dfs.tools.newlogger.display.vk;

import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkBuffer;
import org.lwjgl.vulkan.VkDrawIndirectCommand;

import java.util.List;

public class IndirectDrawBuffer implements VulkanResource{
	
	private final List<BackedVkBuffer> buffers;
	
	public IndirectDrawBuffer(List<BackedVkBuffer> buffers){
		this.buffers = buffers;
	}
	
	public BackedVkBuffer.MemorySession<VkDrawIndirectCommand.Buffer> update(int frameID) throws VulkanCodeException{
		return buffers.get(frameID).updateAs(VkDrawIndirectCommand.Buffer::new);
	}
	
	public VkBuffer getBuffer(int frame){
		return buffers.get(frame).buffer;
	}
	
	public long instanceCapacity(){
		return buffers.getFirst().buffer.size/VkDrawIndirectCommand.SIZEOF;
	}
	
	@Override
	public void destroy(){
		buffers.forEach(BackedVkBuffer::destroy);
	}
}
