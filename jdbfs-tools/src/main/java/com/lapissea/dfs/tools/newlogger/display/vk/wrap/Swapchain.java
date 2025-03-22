package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.Flags;
import com.lapissea.dfs.tools.newlogger.display.vk.VKCalls;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VKImageType;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkImageAspectFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkImageViewType;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkSampleCountFlag;
import com.lapissea.dfs.utils.iterableplus.Iters;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;

import java.util.ArrayList;
import java.util.List;

public class Swapchain extends VulkanResource.DeviceHandleObj{
	
	public final FormatColor       formatColor;
	public final Extent2D          extent;
	public final List<VkImage>     images;
	public final List<VkImageView> imageViews;
	
	public Swapchain(Device device, long handle, VkSwapchainCreateInfoKHR createInfo) throws VulkanCodeException{
		super(device, handle);
		formatColor = new FormatColor(createInfo.imageFormat(), createInfo.imageColorSpace());
		extent = new Extent2D(createInfo.imageExtent());
		
		images = getSwapchainImages();
		
		var views = new ArrayList<VkImageView>(images.size());
		for(VkImage image : images){
			views.add(image.createImageView(VkImageViewType.TYPE_2D, formatColor.format, Flags.of(VkImageAspectFlag.COLOR)));
		}
		imageViews = List.copyOf(views);
	}
	
	private List<VkImage> getSwapchainImages() throws VulkanCodeException{
		try(var stack = MemoryStack.stackPush()){
			var count = stack.mallocInt(1);
			VKCalls.vkGetSwapchainImagesKHR(device, this, count, null);
			var imageRefs = stack.mallocLong(count.get(0));
			VKCalls.vkGetSwapchainImagesKHR(device, this, count, imageRefs);
			
			return Iters.rangeMap(
				0, imageRefs.capacity(),
				i -> new VkImage(device, imageRefs.get(i), extent.as3d(), formatColor.format, VkSampleCountFlag.N1, VKImageType.IMG_2D)
			).toList();
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
