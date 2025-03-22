package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.vk.VulkanTexture;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkDescriptorType;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

public class DescriptorSet{
	
	public final long   handle;
	public final Device device;
	
	public DescriptorSet(long handle, Device device){
		this.handle = handle;
		this.device = device;
	}
	
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

//			var  pDescriptorCopies =  VkCopyDescriptorSet.calloc(1, stack);
			
			VK10.vkUpdateDescriptorSets(device.value, info, null);
		}
		
	}
}
