package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.Flags;
import com.lapissea.dfs.tools.newlogger.display.vk.MappedVkMemory;
import com.lapissea.dfs.tools.newlogger.display.vk.VKCalls;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkMemoryPropertyFlag;
import org.lwjgl.vulkan.VK10;

import java.util.Objects;

public class VkDeviceMemory extends VulkanResource.DeviceHandleObj{
	
	public final long                        allocationSize;
	public final Flags<VkMemoryPropertyFlag> propertyFlags;
	public final long                        nonCoherentAtomSize;
	private      VkBuffer                    boundBuffer;
	
	public VkDeviceMemory(Device device, long handle, long allocationSize, Flags<VkMemoryPropertyFlag> propertyFlags){
		super(device, handle);
		this.allocationSize = allocationSize;
		this.propertyFlags = propertyFlags;
		nonCoherentAtomSize = device.physicalDevice.nonCoherentAtomSize;
	}
	
	public MappedVkMemory map() throws VulkanCodeException{
		return map(0, VK10.VK_WHOLE_SIZE);
	}
	public MappedVkMemory map(long offset, long size) throws VulkanCodeException{
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
