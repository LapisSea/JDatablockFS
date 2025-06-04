package com.lapissea.dfs.tools.newlogger.display.vk;

import com.lapissea.dfs.tools.newlogger.display.ColorRGBA8;
import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkFormat;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkDeviceMemory;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkImage;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkImageView;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkSampler;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public class VulkanTexture implements VulkanResource{
	
	public static CompletableFuture<VulkanTexture> loadTexture(String name, boolean generateMips, Supplier<VulkanCore> coreGet){
		return CompletableFuture.supplyAsync(() -> {
			try(var image = ColorRGBA8.fromJar(name)){
				var levels = generateMips? (int)Math.floor(Math.log(Math.max(image.width, image.height))/Math.log(2)) + 1 : 1;
				var core   = coreGet.get();
				return core.uploadTexture(image.width, image.height, image.getPixels(), VkFormat.R8G8B8A8_UNORM, levels);
			}catch(IOException|VulkanCodeException e){
				throw new RuntimeException("Failed to load image", e);
			}
		});
	}
	
	public final  VkImage        image;
	public final  VkDeviceMemory memory;
	public final  VkImageView    view;
	public final  VkSampler      sampler;
	private final boolean        ownsSampler;
	
	public VulkanTexture(VkImage image, VkDeviceMemory memory, VkImageView view, VkSampler sampler, boolean ownsSampler){
		this.image = image;
		this.memory = memory;
		this.view = view;
		this.sampler = sampler;
		this.ownsSampler = ownsSampler;
	}
	
	
	@Override
	public void destroy(){
		if(ownsSampler) sampler.destroy();
		view.destroy();
		memory.destroy();
		image.destroy();
	}
}
