package com.lapissea.dfs.tools.newlogger.display.vk;

import com.lapissea.dfs.tools.newlogger.display.vk.wrap.DeviceMemory;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;

import java.nio.ByteBuffer;

public class MappedVkMemory implements VulkanResource{
	
	private final DeviceMemory memory;
	private final long         ptr, size;
	
	public MappedVkMemory(DeviceMemory memory, long ptr, long size){
		this.memory = memory;
		this.ptr = ptr;
		this.size = size;
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
	
	@Override
	public void destroy(){
		VK10.vkUnmapMemory(memory.device.value, memory.handle);
	}
}
