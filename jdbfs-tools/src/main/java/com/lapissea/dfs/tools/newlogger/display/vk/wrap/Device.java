package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VKPresentMode;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkColorSpaceKHR;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkFormat;
import com.lapissea.dfs.utils.iterableplus.Iters;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;

import static com.lapissea.dfs.tools.newlogger.display.VUtils.check;

public class Device implements VulkanResource{
	
	public final VkDevice value;
	
	public final PhysicalDevice physicalDevice;
	
	public Device(VkDevice value, PhysicalDevice physicalDevice){
		this.value = value;
		this.physicalDevice = physicalDevice;
		if(!physicalDevice.pDevice.equals(value.getPhysicalDevice())){
			throw new IllegalArgumentException("physical device is not the argument");
		}
	}
	
	public Swapchain createSwapchain(Surface surface){
		Device device = this;
		try(var mem = MemoryStack.stackPush()){
			var pDevice = device.physicalDevice;
			var format = Iters.from(pDevice.formats)
			                  .firstMatching(e -> e.format == VkFormat.R8G8B8A8_UNORM && e.colorSpace == VkColorSpaceKHR.SRGB_NONLINEAR_KHR)
			                  .orElse(pDevice.formats.getFirst());
			
			var presentMode = pDevice.presentModes.contains(VKPresentMode.FIFO)? VKPresentMode.FIFO : pDevice.presentModes.iterator().next();
			int numOfImages = Math.min(pDevice.surfaceCapabilities.minImageCount + 1, pDevice.surfaceCapabilities.maxImageCount);
			
			var usage = VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT|VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
			
			var info = VkSwapchainCreateInfoKHR.calloc(mem).sType$Default();
			info.surface(surface.handle)
			    .minImageCount(numOfImages)
			    .imageFormat(format.format.id)
			    .imageColorSpace(format.colorSpace.id)
			    .imageExtent(pDevice.surfaceCapabilities.currentExtent.toStack(mem))
			    .imageArrayLayers(1)
			    .imageUsage(usage)
			    .imageSharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE)
			    .preTransform(pDevice.surfaceCapabilities.currentTransform.bit)
			    .compositeAlpha(KHRSurface.VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
			    .presentMode(presentMode.id)
			    .clipped(true)
			;
			
			return Swapchain.create(device, info);
		}
	}
	
	public ImageView createImageView(VkImageViewCreateInfo info){
		var ptr = new long[1];
		check(VK10.vkCreateImageView(value, info, null, ptr), "createImageView");
		return new ImageView(ptr[0], this);
	}
	
	public CommandPool createCommandPool(QueueFamilyProps queue, CommandPool.Type commandPoolType){
		try(var mem = MemoryStack.stackPush()){
			int flags = 0;
			if(commandPoolType == CommandPool.Type.SHORT_LIVED) flags |= VK10.VK_COMMAND_POOL_CREATE_TRANSIENT_BIT;
			if(commandPoolType != CommandPool.Type.WRITE_ONCE) flags |= VK10.VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
			
			var info = VkCommandPoolCreateInfo.calloc(mem)
			                                  .sType$Default()
			                                  .flags(flags)
			                                  .queueFamilyIndex(queue.index);
			
			var ptr = mem.mallocLong(1);
			check(VK10.vkCreateCommandPool(value, info, null, ptr), "createCommandPool");
			
			return new CommandPool(ptr.get(0), this, commandPoolType);
		}
	}
	
	@Override
	public void destroy(){
		VK10.vkDestroyDevice(value, null);
	}
}
