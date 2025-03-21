package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.Flags;
import com.lapissea.dfs.tools.newlogger.display.vk.VKCalls;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkFormat;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkImageAspectFlagBits;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkImageViewType;
import com.lapissea.dfs.utils.iterableplus.Iters;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;

import java.util.ArrayList;
import java.util.List;

public class Swapchain implements VulkanResource{
	
	public final long            handle;
	public final VkFormat        format;
	public final Extent2D        extent;
	public final Device          device;
	public final List<VkImage>     images;
	public final List<VkImageView> imageViews;
	
	public Swapchain(long handle, Device device, VkSwapchainCreateInfoKHR createInfo) throws VulkanCodeException{
		this.handle = handle;
		this.device = device;
		format = VkFormat.from(createInfo.imageFormat());
		extent = new Extent2D(createInfo.imageExtent());
		
		var format = VkFormat.from(createInfo.imageFormat());
		
		images = getSwapchainImages();
		
		var views = new ArrayList<VkImageView>(images.size());
		for(VkImage image : images){
			views.add(image.createImageView(VkImageViewType.TYPE_2D, format, Flags.of(VkImageAspectFlagBits.COLOR_BIT)));
		}
		imageViews = List.copyOf(views);
	}
	
	private List<VkImage> getSwapchainImages() throws VulkanCodeException{
		try(var stack = MemoryStack.stackPush()){
			var count = stack.mallocInt(1);
			VKCalls.vkGetSwapchainImagesKHR(device, this, count, null);
			var imageRefs = stack.mallocLong(count.get(0));
			VKCalls.vkGetSwapchainImagesKHR(device, this, count, imageRefs);
			
			return Iters.rangeMap(0, imageRefs.capacity(), i -> new VkImage(imageRefs.get(i), extent, device)).toList();
		}
	}
	
	@Override
	public void destroy(){
		for(VkImageView imageView : imageViews){
			imageView.destroy();
		}
		KHRSwapchain.vkDestroySwapchainKHR(device.value, handle, null);
	}
}
