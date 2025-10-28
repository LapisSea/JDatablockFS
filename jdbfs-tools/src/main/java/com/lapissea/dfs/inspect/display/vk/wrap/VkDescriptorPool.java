package com.lapissea.dfs.inspect.display.vk.wrap;

import com.lapissea.dfs.inspect.display.VulkanCodeException;
import com.lapissea.dfs.inspect.display.vk.VKCalls;
import com.lapissea.dfs.inspect.display.vk.VulkanResource;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class VkDescriptorPool extends VulkanResource.DeviceHandleObj{
	public final AtomicInteger count = new AtomicInteger();
	
	public VkDescriptorPool(Device device, long handle){
		super(device, handle);
	}
	
	public VkDescriptorSetLayout createDescriptorSetLayout(Descriptor.LayoutBinding... bindings) throws VulkanCodeException{
		return createDescriptorSetLayout(Arrays.asList(bindings));
	}
	public VkDescriptorSetLayout createDescriptorSetLayout(List<Descriptor.LayoutBinding> bindings) throws VulkanCodeException{
		
		try(var stack = MemoryStack.stackPush()){
			var descriptorBindings = VkDescriptorSetLayoutBinding.malloc(bindings.size(), stack);
			for(int i = 0; i<bindings.size(); i++){
				var binding = bindings.get(i);
				binding.set(descriptorBindings.position(i), stack);
			}
			descriptorBindings.position(0);
			
			var pCreateInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
			                                                 .sType$Default()
			                                                 .pBindings(descriptorBindings);
			
			return VKCalls.vkCreateDescriptorSetLayout(this, pCreateInfo);
		}
		
	}
	
	@Override
	public void destroy(){
		VK10.vkDestroyDescriptorPool(device.value, handle, null);
	}
}
