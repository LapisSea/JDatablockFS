package com.lapissea.dfs.tools.newlogger.display.vk;

import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkBuffer;
import com.lapissea.util.function.UnsafeConsumer;

import java.nio.ByteBuffer;
import java.util.List;

public class UniformBuffer implements VulkanResource{
	
	private final List<BackedVkBuffer> buffers;
	public final  boolean              ssbo;
	
	public UniformBuffer(List<BackedVkBuffer> buffers, boolean ssbo){
		this.buffers = buffers;
		this.ssbo = ssbo;
	}
	
	public <E extends Throwable> void update(int frameID, UnsafeConsumer<ByteBuffer, E> uniformPopulator) throws VulkanCodeException, E{
		var buf = buffers.get(frameID);
		buf.update(uniformPopulator);
	}
	public MappedVkMemory update(int frameID) throws VulkanCodeException{
		return buffers.get(frameID).update();
	}
	
	public VkBuffer getBuffer(int frame){
		return buffers.get(frame).buffer;
	}
	
	public long size(){
		return buffers.getFirst().buffer.size;
	}
	
	@Override
	public void destroy(){
		buffers.forEach(BackedVkBuffer::destroy);
	}
}
