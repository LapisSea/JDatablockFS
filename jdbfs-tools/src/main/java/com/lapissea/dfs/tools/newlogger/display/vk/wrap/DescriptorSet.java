package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkDescriptorType;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

public class DescriptorSet implements VulkanResource{
	
	public final long   handle;
	public final Device device;
	
	public DescriptorSet(long handle, Device device){
		this.handle = handle;
		this.device = device;
	}
	
	public void update(VkBuffer buffer){
		
		try(MemoryStack stack = MemoryStack.stackPush()){
			var pBufferInfo = VkDescriptorBufferInfo.malloc(1, stack);
			pBufferInfo.buffer(buffer.handle)
			           .offset(0)
			           .range(buffer.size);
			
			var pDescriptorWrites = VkWriteDescriptorSet.calloc(1, stack);
			
			pDescriptorWrites.sType$Default()
			                 .dstSet(handle)
			                 .dstBinding(0)
			                 .dstArrayElement(0)
			                 .descriptorCount(1)
			                 .descriptorType(VkDescriptorType.STORAGE_BUFFER.id)
			                 .pBufferInfo(pBufferInfo);

//			var  pDescriptorCopies =  VkCopyDescriptorSet.calloc(1, stack);
			
			VK10.vkUpdateDescriptorSets(device.value, pDescriptorWrites, null);
		}
		
	}
	
	@Override
	public void destroy(){
		VK10.vkDestroyDescriptorSetLayout(device.value, handle, null);
	}
}
