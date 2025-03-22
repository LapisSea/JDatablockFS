package com.lapissea.dfs.tools.newlogger.display.vk;

import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkDeviceMemory;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkImage;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkImageView;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkSampler;

public class VulkanTexture implements VulkanResource{
	
	public final VkImage        image;
	public final VkDeviceMemory memory;
	public final VkImageView    view;
	public final VkSampler      sampler;
	
	public VulkanTexture(VkImage image, VkDeviceMemory memory, VkImageView view, VkSampler sampler){
		this.image = image;
		this.memory = memory;
		this.view = view;
		this.sampler = sampler;
	}
	
	
	@Override
	public void destroy(){
		memory.destroy();
		view.destroy();
		sampler.destroy();
		image.destroy();
	}
}
