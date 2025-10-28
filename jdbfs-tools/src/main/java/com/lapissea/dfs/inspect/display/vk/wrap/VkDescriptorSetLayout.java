package com.lapissea.dfs.inspect.display.vk.wrap;

import com.lapissea.dfs.inspect.display.VulkanCodeException;
import com.lapissea.dfs.inspect.display.vk.VKCalls;
import com.lapissea.dfs.inspect.display.vk.VulkanCore;
import com.lapissea.dfs.inspect.display.vk.VulkanResource;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;

import java.util.List;

public class VkDescriptorSetLayout extends VulkanResource.DeviceHandleObj{
	
	public final VkDescriptorPool pool;
	
	public VkDescriptorSetLayout(VkDescriptorPool pool, long handle){
		super(pool.device, handle);
		this.pool = pool;
	}
	
	public VkDescriptorSet.PerFrame createDescriptorSetsPerFrame() throws VulkanCodeException{
		return new VkDescriptorSet.PerFrame(createDescriptorSets(VulkanCore.MAX_IN_FLIGHT_FRAMES));
	}
	public VkDescriptorSet createDescriptorSet() throws VulkanCodeException{
		return createDescriptorSets(1).getFirst();
	}
	public List<VkDescriptorSet> createDescriptorSets(int count) throws VulkanCodeException{
		try(var stack = MemoryStack.stackPush()){
			var layouts = stack.mallocLong(count);
			for(int i = 0; i<count; i++){
				layouts.put(i, handle);
			}
			var pAllocateInfo = VkDescriptorSetAllocateInfo.calloc(stack)
			                                               .sType$Default()
			                                               .descriptorPool(pool.handle)
			                                               .pSetLayouts(layouts);
			synchronized(pool){
				var res = VKCalls.vkAllocateDescriptorSets(device, pool, pAllocateInfo);
				pool.count.addAndGet(count);
				return res;
			}
		}
	}
	
	@Override
	public void destroy(){
		VK10.vkDestroyDescriptorSetLayout(device.value, handle, null);
	}
}
