package com.lapissea.dfs.inspect.display.vk.wrap;

import com.lapissea.dfs.inspect.display.VulkanCodeException;
import com.lapissea.dfs.inspect.display.vk.Flags;
import com.lapissea.dfs.inspect.display.vk.MappedVkMemory;
import com.lapissea.dfs.inspect.display.vk.VKCalls;
import com.lapissea.dfs.inspect.display.vk.VulkanResource;
import com.lapissea.dfs.inspect.display.vk.enums.VkMemoryPropertyFlag;
import org.lwjgl.vulkan.VK10;

import java.util.Objects;

public class VkDeviceMemory extends VulkanResource.DeviceHandleObj{
	
	public final long                        allocationSize;
	public final Flags<VkMemoryPropertyFlag> propertyFlags;
	private      VkBuffer                    boundBuffer;
	
	public VkDeviceMemory(Device device, long handle, long allocationSize, Flags<VkMemoryPropertyFlag> propertyFlags){
		super(device, handle);
		this.allocationSize = allocationSize;
		this.propertyFlags = propertyFlags;
	}
	
	public MappedVkMemory map() throws VulkanCodeException{
		return map(0, VK10.VK_WHOLE_SIZE);
	}
	public MappedVkMemory map(long offset, long size) throws VulkanCodeException{
		
		if(propertyFlags.contains(VkMemoryPropertyFlag.HOST_CACHED)){
			var actualSize = size == VK10.VK_WHOLE_SIZE? boundBuffer.size - offset : size;
			
			var physicalDevice = device.physicalDevice;
			
			var alignedOffset = physicalDevice.alignToAtomSizeDown(offset);
			var end           = actualSize + offset;
			var alignedEnd    = physicalDevice.alignToAtomSizeUp(end);
			var alignedSize   = alignedEnd - alignedOffset;
			
			if(alignedOffset + alignedSize>boundBuffer.size){
				throw new AssertionError(alignedOffset + "+" + alignedSize + ">" + boundBuffer.size +
				                         " (" + (alignedOffset + alignedSize) + ">" + boundBuffer.size + ")");
			}
			
			var mapAligned = VKCalls.vkMapMemory(this, alignedOffset, alignedSize, 0);
			
			var offsetChange = offset - alignedOffset;
			var flushInfo    = new MappedVkMemory.FlushInfo(alignedOffset, alignedSize);
			return new MappedVkMemory(this, mapAligned.getAddress() + offsetChange, actualSize, offset, flushInfo);
		}
		
		return VKCalls.vkMapMemory(this, offset, size, 0);
	}
	
	@Override
	public void destroy(){
		VK10.vkFreeMemory(device.value, handle, null);
	}
	
	@Override
	public String toString(){
		return "VkDeviceMemory{" + allocationSize + "bytes, " + propertyFlags + "}";
	}
	
	public void boundBuffer(VkBuffer buffer){
		boundBuffer = Objects.requireNonNull(buffer);
	}
	public VkBuffer getBoundBuffer(){
		return boundBuffer;
	}
}
