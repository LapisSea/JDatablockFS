package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.VKCalls;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;

import java.util.List;

public class DescriptorPool extends VulkanResource.DeviceHandleObj{
	
	public DescriptorPool(Device device, long handle){ super(device, handle); }
	
	public DescriptorSetLayout createDescriptorSetLayout(List<DescriptorSetLayoutBinding> bindings) throws VulkanCodeException{
		
		try(var stack = MemoryStack.stackPush()){
			var descriptorBindings = VkDescriptorSetLayoutBinding.malloc(bindings.size(), stack);
			for(int i = 0; i<bindings.size(); i++){
				var binding = bindings.get(i);
				binding.set(descriptorBindings.position(i));
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
