package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.VKCalls;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanTexture;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkDescriptorType;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.util.List;

public class Descriptor{
	
	public static final class VkPool extends VulkanResource.DeviceHandleObj{
		
		public VkPool(Device device, long handle){ super(device, handle); }
		
		public VkLayout createDescriptorSetLayout(List<DescriptorSetLayoutBinding> bindings) throws VulkanCodeException{
			
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
	
	
	public static class VkLayout extends VulkanResource.DeviceHandleObj{
		
		public final VkPool pool;
		
		public VkLayout(VkPool pool, long handle){
			super(pool.device, handle);
			this.pool = pool;
		}
		
		public List<VkSet> createDescriptorSets(int count) throws VulkanCodeException{
			try(var stack = MemoryStack.stackPush()){
				var layouts = stack.mallocLong(count);
				for(int i = 0; i<count; i++){
					layouts.put(i, handle);
				}
				var pAllocateInfo = VkDescriptorSetAllocateInfo.calloc(stack)
				                                               .sType$Default()
				                                               .descriptorPool(pool.handle)
				                                               .pSetLayouts(layouts);
				
				return VKCalls.vkAllocateDescriptorSets(device, pAllocateInfo);
			}
		}
		
		@Override
		public void destroy(){
			VK10.vkDestroyDescriptorSetLayout(device.value, handle, null);
		}
	}
	
	
	public static class VkSet extends VulkanResource.DeviceHandleObj{
		
		public VkSet(Device device, long handle){ super(device, handle); }
		
		public void update(VkBuffer buffer, VkBuffer uniformBuffer, VulkanTexture texture){
			
			try(MemoryStack stack = MemoryStack.stackPush()){
				
				var info = VkWriteDescriptorSet.calloc(3, stack);
				
				for(int i = 0; i<info.capacity(); i++){
					info.position(i)
					    .sType$Default()
					    .dstSet(handle)
					    .dstArrayElement(0)
					    .descriptorCount(1);
				}
				
				info.position(0)
				    .dstBinding(0)
				    .descriptorType(VkDescriptorType.STORAGE_BUFFER.id)
				    .pBufferInfo(
					    VkDescriptorBufferInfo.malloc(1, stack)
					                          .buffer(buffer.handle)
					                          .offset(0)
					                          .range(buffer.size)
				    );
				
				
				info.position(1)
				    .dstBinding(1)
				    .descriptorType(VkDescriptorType.UNIFORM_BUFFER.id)
				    .pBufferInfo(
					    VkDescriptorBufferInfo.malloc(1, stack)
					                          .buffer(uniformBuffer.handle)
					                          .offset(0)
					                          .range(uniformBuffer.size)
				    );
				
				
				info.position(2)
				    .dstBinding(2)
				    .descriptorType(VkDescriptorType.COMBINED_IMAGE_SAMPLER.id)
				    .pImageInfo(
					    VkDescriptorImageInfo.malloc(1, stack)
					                         .sampler(texture.sampler.handle)
					                         .imageView(texture.view.handle)
					                         .imageLayout(VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
				    );
				
				info.position(0);
				
				//var  pDescriptorCopies =  VkCopyDescriptorSet.calloc(1, stack);
				
				VK10.vkUpdateDescriptorSets(device.value, info, null);
			}
			
		}
		
		@Override
		public void destroy(){ }
	}
}
