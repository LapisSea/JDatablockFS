package com.lapissea.dfs.inspect.display.vk;

import com.lapissea.dfs.inspect.display.VulkanCodeException;
import com.lapissea.dfs.inspect.display.vk.wrap.VkBuffer;
import org.lwjgl.vulkan.VkDrawIndirectCommand;

public class IndirectDrawBuffer implements VulkanResource{
	
	public static class PerFrame implements VulkanResource{
		
		private final IndirectDrawBuffer[] buffers;
		
		public PerFrame(IndirectDrawBuffer[] buffers){
			this.buffers = buffers;
		}
		
		public IndirectDrawBuffer getBuffer(int frame){
			return buffers[frame];
		}
		
		public IndirectWriter update(int frameID) throws VulkanCodeException{
			return getBuffer(frameID).update();
		}
		public long instanceCapacity(){
			return buffers[0].instanceCapacity();
		}
		
		@Override
		public void destroy() throws VulkanCodeException{
			for(var e : buffers){
				e.destroy();
			}
		}
	}
	
	public static final class IndirectWriter implements AutoCloseable{
		private final MappedVkMemory               mem;
		public        VkDrawIndirectCommand.Buffer buffer;
		private IndirectWriter(MappedVkMemory mem){
			this.mem = mem;
			buffer = new VkDrawIndirectCommand.Buffer(mem.getBuffer());
		}
		
		public void clear(){ buffer.clear(); }
		
		public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance){
			buffer.vertexCount(vertexCount).instanceCount(instanceCount).firstVertex(firstVertex).firstInstance(firstInstance);
			buffer.position(buffer.position() + 1);
		}
		
		@Override
		public void close() throws VulkanCodeException{ mem.close(); }
		public void setPos(int pos){
			buffer.position(pos);
		}
	}
	
	public final BackedVkBuffer buffer;
	
	public IndirectDrawBuffer(BackedVkBuffer buffer){
		this.buffer = buffer;
	}
	
	public IndirectWriter update() throws VulkanCodeException{
		return new IndirectWriter(buffer.update());
	}
	
	public VkBuffer getVkBuffer(){
		return buffer.buffer;
	}
	
	public long instanceCapacity(){
		return buffer.buffer.size/VkDrawIndirectCommand.SIZEOF;
	}
	
	@Override
	public void destroy(){
		buffer.destroy();
	}
}
