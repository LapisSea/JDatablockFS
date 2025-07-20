package com.lapissea.dfs.tools.newlogger.display.vk;

import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkDeviceMemory;
import com.lapissea.util.function.UnsafeConsumer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkMappedMemoryRange;

import java.nio.ByteBuffer;
import java.util.Objects;

public class MappedVkMemory implements AutoCloseable{
	public record FlushInfo(long offset, long size){ }
	
	
	private final VkDeviceMemory memory;
	private final long           ptr, size, mapOffset;
	private final FlushInfo flushInfo;
	
	public MappedVkMemory(VkDeviceMemory memory, long ptr, long size, long mapOffset, FlushInfo flushInfo){
		this.memory = Objects.requireNonNull(memory);
		this.ptr = ptr;
		this.size = size;
		this.mapOffset = mapOffset;
		this.flushInfo = flushInfo;
		assert size>0 : size;
	}
	
	public void put(ByteBuffer src){
		put(src, 0);
	}
	public void put(ByteBuffer src, long dstOffset){
		var toCopy = src.remaining();
		
		var offPtr    = ptr + dstOffset;
		var remaining = size - dstOffset;
		if(remaining<0) throw new IndexOutOfBoundsException("Offset is larger than buffer size");
		if(toCopy>remaining) throw new IndexOutOfBoundsException("Source is bigger than destination");
		
		MemoryUtil.memCopy(MemoryUtil.memAddress(src), offPtr, toCopy);
		src.position(src.position() + toCopy);
	}
	
	public <E extends Throwable> void populate(UnsafeConsumer<ByteBuffer, E> populator) throws E{
		var bb = getBuffer();
		populator.accept(bb);
	}
	public ByteBuffer getBuffer(){
		return MemoryUtil.memByteBuffer(ptr, Math.toIntExact(size));
	}
	public long getAddress(){
		return ptr;
	}
	
	public long getMapOffset(){
		return mapOffset;
	}
	
	@Override
	public void close() throws VulkanCodeException{
		if(flushInfo != null){
			try(var stack = MemoryStack.stackPush()){
				var memoryRange = VkMappedMemoryRange.malloc(stack);
				memoryRange.sType$Default()
				           .pNext(0)
				           .memory(memory.handle)
				           .offset(flushInfo.offset)
				           .size(flushInfo.size);
				VKCalls.vkFlushMappedMemoryRanges(memory.device, memoryRange);
			}
		}
		VK10.vkUnmapMemory(memory.device.value, memory.handle);
	}
}
