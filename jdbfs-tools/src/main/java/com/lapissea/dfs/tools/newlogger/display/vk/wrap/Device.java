package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.VKCalls;
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
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;

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
	
	public Swapchain createSwapchain(Surface surface, VKPresentMode preferredPresentMode) throws VulkanCodeException{
		try(var mem = MemoryStack.stackPush()){
			var format = Iters.from(physicalDevice.formats)
			                  .firstMatching(e -> e.format == VkFormat.R8G8B8A8_UNORM && e.colorSpace == VkColorSpaceKHR.SRGB_NONLINEAR_KHR)
			                  .orElse(physicalDevice.formats.getFirst());
			
			SurfaceCapabilities surfaceCapabilities = surface.getCapabilities(physicalDevice);
			
			var presentMode = physicalDevice.presentModes.contains(preferredPresentMode)?
			                  preferredPresentMode :
			                  physicalDevice.presentModes.iterator().next();
			
			int numOfImages = Math.min(surfaceCapabilities.minImageCount + 1, surfaceCapabilities.maxImageCount);
			
			var usage = VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT|VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
			
			var info = VkSwapchainCreateInfoKHR.calloc(mem).sType$Default();
			info.surface(surface.handle)
			    .minImageCount(numOfImages)
			    .imageFormat(format.format.id)
			    .imageColorSpace(format.colorSpace.id)
			    .imageExtent(surfaceCapabilities.currentExtent.toStack(mem))
			    .imageArrayLayers(1)
			    .imageUsage(usage)
			    .imageSharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE)
			    .preTransform(surfaceCapabilities.currentTransform.bit)
			    .compositeAlpha(KHRSurface.VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
			    .presentMode(presentMode.id)
			    .clipped(true)
			;
			
			return VKCalls.vkCreateSwapchainKHR(this, info);
		}
	}
	
	public ImageView createImageView(VkImageViewCreateInfo info) throws VulkanCodeException{
		return VKCalls.vkCreateImageView(this, info);
	}
	
	public CommandPool createCommandPool(QueueFamilyProps queue, CommandPool.Type commandPoolType) throws VulkanCodeException{
		try(var mem = MemoryStack.stackPush()){
			int flags = 0;
			if(commandPoolType == CommandPool.Type.SHORT_LIVED) flags |= VK10.VK_COMMAND_POOL_CREATE_TRANSIENT_BIT;
			if(commandPoolType != CommandPool.Type.WRITE_ONCE) flags |= VK10.VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
			
			var info = VkCommandPoolCreateInfo.calloc(mem)
			                                  .sType$Default()
			                                  .flags(flags)
			                                  .queueFamilyIndex(queue.index);
			
			var res = VKCalls.vkCreateCommandPool(this, info);
			return new CommandPool(res, this, commandPoolType);
		}
	}
	
	public VkQueue getQueue(QueueFamilyProps family, int queueIndex){
		try(var stack = MemoryStack.stackPush()){
			var ptr = stack.mallocPointer(1);
			VK10.vkGetDeviceQueue(value, family.index, queueIndex, ptr);
			return new VkQueue(ptr.get(0), value);
		}
	}
	
	public VulkanSemaphore createSemaphore() throws VulkanCodeException{
		try(var stack = MemoryStack.stackPush()){
			
			var info = VkSemaphoreCreateInfo.calloc(stack)
			                                .sType$Default();
			
			return VKCalls.vkCreateSemaphore(this, info);
		}
	}
	
	public RenderPass.Builder buildRenderPass(){
		return new RenderPass.Builder(this);
	}
	
	@Override
	public void destroy(){
		VK10.vkDestroyDevice(value, null);
	}
}
