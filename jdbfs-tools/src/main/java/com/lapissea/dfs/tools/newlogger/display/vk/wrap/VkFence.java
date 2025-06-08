package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.VKCalls;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkResult;
import org.lwjgl.vulkan.VK10;

import java.time.Duration;

public class VkFence extends VulkanResource.DeviceHandleObj{
	
	public VkFence(Device device, long handle){ super(device, handle); }
	
	public void waitFor() throws VulkanCodeException{
		VKCalls.vkWaitForFence(this, Long.MAX_VALUE);
	}
	public boolean waitFor(Duration timeout) throws VulkanCodeException{
		try{
			VKCalls.vkWaitForFence(this, timeout.toNanos());
			return true;
		}catch(VulkanCodeException.Timeout e){
			return false;
		}
	}
	public void reset() throws VulkanCodeException{
		VKCalls.vkResetFences(this);
	}
	public void waitReset() throws VulkanCodeException{
		waitFor();
		reset();
	}
	
	public boolean isSignaled() throws VulkanCodeException{
		var res = VkResult.from(VK10.vkGetFenceStatus(device.value, handle));
		return switch(res){
			case SUCCESS -> true;
			case NOT_READY -> false;
			case ERROR_DEVICE_LOST -> throw new VulkanCodeException("vkGetFenceStatus", VkResult.ERROR_DEVICE_LOST);
			default -> throw new IllegalStateException("Unexpected result: " + res);
		};
	}
	
	@Override
	public void destroy(){
		VK10.vkDestroyFence(device.value, handle, null);
	}
}
