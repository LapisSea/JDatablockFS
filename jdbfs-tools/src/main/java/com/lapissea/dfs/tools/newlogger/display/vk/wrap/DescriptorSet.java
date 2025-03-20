package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkDescriptorType;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

public class DescriptorSet{
	
	public final long   handle;
	public final Device device;
	
	public DescriptorSet(long handle, Device device){
		this.handle = handle;
		this.device = device;
	}
	
	public void update(VkBuffer buffer, VkBuffer uniformBuffer){
		
		try(MemoryStack stack = MemoryStack.stackPush()){
			
			var pDescriptorWrites = VkWriteDescriptorSet.calloc(2, stack);
			
			pDescriptorWrites.sType$Default()
			                 .dstSet(handle)
			                 .dstBinding(0)
			                 .dstArrayElement(0)
			                 .descriptorCount(1)
			                 .descriptorType(VkDescriptorType.STORAGE_BUFFER.id)
			                 .pBufferInfo(
				                 VkDescriptorBufferInfo.malloc(1, stack)
				                                       .buffer(buffer.handle)
				                                       .offset(0)
				                                       .range(buffer.size)
			                 );
			
			
			pDescriptorWrites.position(1)
			                 .sType$Default()
			                 .dstSet(handle)
			                 .dstBinding(1)
			                 .dstArrayElement(0)
			                 .descriptorCount(1)
			                 .descriptorType(VkDescriptorType.UNIFORM_BUFFER.id)
			                 .pBufferInfo(
				                 VkDescriptorBufferInfo.malloc(1, stack)
				                                       .buffer(uniformBuffer.handle)
				                                       .offset(0)
				                                       .range(uniformBuffer.size)
			                 );
			
			pDescriptorWrites.position(0);

//			var  pDescriptorCopies =  VkCopyDescriptorSet.calloc(1, stack);
			
			VK10.vkUpdateDescriptorSets(device.value, pDescriptorWrites, null);
		}
		
	}
}
