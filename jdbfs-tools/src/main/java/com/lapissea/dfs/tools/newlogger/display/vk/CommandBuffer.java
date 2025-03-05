package com.lapissea.dfs.tools.newlogger.display.vk;

import com.lapissea.dfs.tools.newlogger.display.vk.wrap.CommandPool;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Device;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

public class CommandBuffer implements VulkanResource{
	
	public enum State{
		INITIAL, // to recording
		RECORDING, // to executable, invalid
		EXECUTABLE,// to pending, invalid
		PENDING,// to executable, invalid
		INVALID //to initial
	}
	
	private final VkCommandBuffer val;
	private final CommandPool     pool;
	private final Device          device;
	private       State           state = State.INITIAL;
	
	public CommandBuffer(long handle, CommandPool pool, Device device){
		this.val = new VkCommandBuffer(handle, device.value);
		this.pool = pool;
		this.device = device;
	}
	
	@Override
	public void destroy(){
		VK10.vkFreeCommandBuffers(device.value, pool.handle, val);
	}
	
	@Override
	public String toString(){
		return "CommandBuffer{" + state + '}';
	}
}
