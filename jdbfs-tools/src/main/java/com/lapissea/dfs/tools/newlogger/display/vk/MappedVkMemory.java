package com.lapissea.dfs.tools.newlogger.display.vk;

import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkMemoryPropertyFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkDeviceMemory;
import com.lapissea.util.function.UnsafeConsumer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkMappedMemoryRange;

import java.nio.ByteBuffer;
import java.util.Objects;

public class MappedVkMemory implements AutoCloseable{
	
	private final VkDeviceMemory memory;
	private final long           ptr, mapOffset, mapSize;
	
	public MappedVkMemory(VkDeviceMemory memory, long ptr, long mapOffset, long mapSize){
		this.memory = Objects.requireNonNull(memory);
		this.ptr = ptr;
		this.mapOffset = mapOffset;
		this.mapSize = mapSize;
	}
	
	private long getActualSize(){
		return mapSize == VK10.VK_WHOLE_SIZE? memory.getBoundBuffer().size : mapSize;
	}
	
	public void put(ByteBuffer src){
		put(src, 0);
	}
	public void put(ByteBuffer src, long dstOffset){
		var toCopy = src.remaining();
		
		var offPtr    = ptr + dstOffset;
		var remaining = getActualSize() - dstOffset;
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
		return MemoryUtil.memByteBuffer(ptr, Math.toIntExact(getActualSize()));
	}
	public long getAddress(){
		return ptr;
	}
	
	public long getMapOffset(){
		return mapOffset;
	}
	
	@Override
	public void close() throws VulkanCodeException{
		if(memory.propertyFlags.contains(VkMemoryPropertyFlag.HOST_CACHED)){
			try(var stack = MemoryStack.stackPush()){
				long atomSize;
				if(mapSize == VK10.VK_WHOLE_SIZE) atomSize = VK10.VK_WHOLE_SIZE;
				else{
					var cas = memory.nonCoherentAtomSize;
					atomSize = Math.min(Math.ceilDiv(mapSize, cas)*cas, memory.getBoundBuffer().size - mapOffset);
				}
				var memoryRange = VkMappedMemoryRange.malloc(stack);
				memoryRange.sType$Default()
				           .pNext(0)
				           .memory(memory.handle)
				           .offset(mapOffset)
				           .size(atomSize);
				VKCalls.vkFlushMappedMemoryRanges(memory.device, memoryRange);
			}
		}
		VK10.vkUnmapMemory(memory.device.value, memory.handle);
	}
}
