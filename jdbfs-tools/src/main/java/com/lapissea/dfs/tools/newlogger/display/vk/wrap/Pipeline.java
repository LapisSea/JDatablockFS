package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import org.lwjgl.vulkan.VK10;

public class Pipeline extends VulkanResource.DeviceHandleObj{
	
	public static class Layout extends VulkanResource.DeviceHandleObj{
		
		public Layout(Device device, long handle){ super(device, handle); }
		
		@Override
		public void destroy(){
			VK10.vkDestroyPipelineLayout(device.value, handle, null);
		}
	}
	
	public final Layout layout;
	
	public Pipeline(Device device, long handle, Layout layout){
		super(device, handle);
		this.layout = layout;
	}
	
	@Override
	public void destroy(){
		layout.destroy();
		VK10.vkDestroyPipeline(device.value, handle, null);
	}
}
