package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkFormat;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkImageAspectFlagBits;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkImageViewType;
import com.lapissea.dfs.utils.iterableplus.Iters;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;

import java.util.List;
import java.util.Set;

import static com.lapissea.dfs.tools.newlogger.display.VUtils.check;

public class Swapchain implements VulkanResource{
	
	public static Swapchain create(Device device, VkSwapchainCreateInfoKHR createInfo){
		var ptr = new long[1];
		check(KHRSwapchain.vkCreateSwapchainKHR(device.value, createInfo, null, ptr), "createSwapchain");
		return new Swapchain(ptr[0], device, createInfo);
	}
	
	private final long            handle;
	public final  Device          device;
	public final  List<Image>     images;
	public final  List<ImageView> imageViews;
	
	public Swapchain(long handle, Device device, VkSwapchainCreateInfoKHR createInfo){
		this.handle = handle;
		this.device = device;
		
		var format = VkFormat.from(createInfo.imageFormat());
		
		images = getSwapchainImages();
		
		imageViews = Iters.from(images).map(i -> i.createImageView(
			VkImageViewType.TYPE_2D, format, Set.of(VkImageAspectFlagBits.COLOR_BIT), 1, 1
		)).toList();
	}
	
	private List<Image> getSwapchainImages(){
		try(var stack = MemoryStack.stackPush()){
			var count = stack.mallocInt(1);
			check(KHRSwapchain.vkGetSwapchainImagesKHR(device.value, handle, count, null), "getSwapchainImages");
			var imageRefs = stack.mallocLong(count.get(0));
			check(KHRSwapchain.vkGetSwapchainImagesKHR(device.value, handle, count, imageRefs), "getSwapchainImages");
			
			return Iters.rangeMap(0, imageRefs.capacity(), i -> new Image(imageRefs.get(i), device)).toList();
		}
	}
	
	@Override
	public void destroy(){
		for(ImageView imageView : imageViews){
			imageView.destroy();
		}
		KHRSwapchain.vkDestroySwapchainKHR(device.value, handle, null);
	}
}
