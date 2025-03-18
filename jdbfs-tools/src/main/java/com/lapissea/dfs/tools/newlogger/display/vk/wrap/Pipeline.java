package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.Flags;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import org.lwjgl.vulkan.VK10;

import java.util.List;

public class Pipeline implements VulkanResource{
	
	public static class Layout implements VulkanResource{
		
		public final long   handle;
		public final Device device;
		
		public Layout(long handle, Device device){
			this.handle = handle;
			this.device = device;
		}
		
		@Override
		public void destroy(){
			VK10.vkDestroyPipelineLayout(device.value, handle, null);
		}
	}
	
	public final long   handle;
	public final Device device;
	public final Layout layout;
	
	private DescriptorPool      descriptorPool;
	private DescriptorSetLayout descriptorSetLayout;
	private List<DescriptorSet> descriptorSets;
	
	public Pipeline(long handle, Layout layout, Device device){
		this.handle = handle;
		this.layout = layout;
		this.device = device;
		
	}
	
	public void initDescriptor(int descriptorSetCount) throws VulkanCodeException{
		assert descriptorPool == null;
		
		descriptorPool = device.createDescriptorPool(descriptorSetCount, Flags.of());
		descriptorSetLayout = descriptorPool.createDescriptorSetLayout();
		descriptorSets = descriptorSetLayout.createDescriptorSets(descriptorSetCount);
		
		for(DescriptorSet descriptorSet : descriptorSets){
			descriptorSet.update();
		}
	}
	
	@Override
	public void destroy(){
		layout.destroy();
		VK10.vkDestroyPipeline(device.value, handle, null);
	}
}
