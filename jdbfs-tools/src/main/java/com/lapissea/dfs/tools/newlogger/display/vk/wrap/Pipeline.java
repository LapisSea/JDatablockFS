package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import org.lwjgl.vulkan.VK10;

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
	
	
	public Pipeline(long handle, Layout layout, Device device){
		this.handle = handle;
		this.layout = layout;
		this.device = device;
	}
	
	@Override
	public void destroy(){
		layout.destroy();
		VK10.vkDestroyPipeline(device.value, handle, null);
	}
}
