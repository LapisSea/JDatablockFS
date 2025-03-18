package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.VKCalls;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;

import java.util.List;

public class DescriptorSetLayout implements VulkanResource{
	
	public final long           handle;
	public final DescriptorPool pool;
	
	public DescriptorSetLayout(long handle, DescriptorPool pool){
		this.handle = handle;
		this.pool = pool;
	}
	
	public List<DescriptorSet> createDescriptorSets(int count) throws VulkanCodeException{
		try(var stack = MemoryStack.stackPush()){
			var layouts = stack.mallocLong(count);
			for(int i = 0; i<count; i++){
				layouts.put(i, handle);
			}
			var pAllocateInfo = VkDescriptorSetAllocateInfo.calloc(stack)
			                                               .sType$Default()
			                                               .descriptorPool(pool.handle)
			                                               .pSetLayouts(layouts);
			
			return VKCalls.vkAllocateDescriptorSets(pool.device, pAllocateInfo);
		}
	}
	
	@Override
	public void destroy(){
		VK10.vkDestroyDescriptorSetLayout(pool.device.value, handle, null);
	}
}
