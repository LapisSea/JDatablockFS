package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.VKCalls;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.util.List;

public class VkDescriptorSet extends VulkanResource.DeviceHandleObj{
	
	private final VkDescriptorPool pool;
	
	public VkDescriptorSet(Device device, long handle, VkDescriptorPool pool){
		super(device, handle);
		this.pool = pool;
	}
	
	public void update(List<Descriptor.LayoutDescription.BindData> bindings, int id){
		
		try(MemoryStack stack = MemoryStack.stackPush()){
			
			var info = VkWriteDescriptorSet.calloc(bindings.size(), stack);
			
			for(int i = 0; i<bindings.size(); i++){
				var binding = bindings.get(i);
				binding.write(info.position(i), id);
				info.position(i)
				    .sType$Default()
				    .dstSet(handle)
				    .dstArrayElement(0)
				    .descriptorCount(1);
			}
			info.position(0);
			
			VK10.vkUpdateDescriptorSets(device.value, info, null);
		}
		
	}
	
	@Override
	public void destroy() throws VulkanCodeException{
		VKCalls.vkFreeDescriptorSets(device, pool, this);
	}
}
