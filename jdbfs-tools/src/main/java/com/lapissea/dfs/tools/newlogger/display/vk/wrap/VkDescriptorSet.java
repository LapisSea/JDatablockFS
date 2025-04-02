package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.VKCalls;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.util.AbstractList;
import java.util.List;

public class VkDescriptorSet extends VulkanResource.DeviceHandleObj{
	
	public static final class PerFrame extends AbstractList<VkDescriptorSet> implements VulkanResource{
		
		private final VkDescriptorSet[] sets;
		
		public PerFrame(List<VkDescriptorSet> sets){
			this.sets = sets.toArray(VkDescriptorSet[]::new);
		}
		
		public void updateAll(List<Descriptor.LayoutDescription.BindData> bindings){
			for(int i = 0; i<sets.length; i++){
				sets[i].update(bindings, i);
			}
		}
		
		@Override
		public void destroy() throws VulkanCodeException{
			for(VkDescriptorSet set : sets){
				set.destroy();
			}
		}
		
		@Override
		public VkDescriptorSet get(int index){ return sets[index]; }
		@Override
		public int size(){ return sets.length; }
	}
	
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
