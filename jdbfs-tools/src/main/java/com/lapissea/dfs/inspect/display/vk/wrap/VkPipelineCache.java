package com.lapissea.dfs.inspect.display.vk.wrap;

import com.lapissea.dfs.inspect.display.VulkanCodeException;
import com.lapissea.dfs.inspect.display.vk.VKCalls;
import com.lapissea.dfs.inspect.display.vk.VulkanResource;
import org.lwjgl.vulkan.VK10;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

public class VkPipelineCache extends VulkanResource.DeviceHandleObj{
	
	public VkPipelineCache(Device device, long handle){ super(device, handle); }
	
	public ByteBuffer getCacheData() throws VulkanCodeException{
		var size = VKCalls.vkGetPipelineCacheData(this, null);
		var mem  = ByteBuffer.allocateDirect(Math.toIntExact(size));
		VKCalls.vkGetPipelineCacheData(this, mem);
		return mem;
	}
	
	public void saveDataTo(File cacheFile) throws IOException, VulkanCodeException{
		var size = VKCalls.vkGetPipelineCacheData(this, null);
		try(var file = new RandomAccessFile(cacheFile, "rw")){
			file.setLength(size);
			var channel      = file.getChannel();
			var mappedMemory = channel.map(FileChannel.MapMode.READ_WRITE, 0, channel.size());
			VKCalls.vkGetPipelineCacheData(this, mappedMemory);
		}
	}
	
	@Override
	public void destroy(){
		VK10.vkDestroyPipelineCache(device.value, handle, null);
	}
}
