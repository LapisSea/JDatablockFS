package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkFormat;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkImageAspectFlagBits;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkImageViewType;
import com.lapissea.dfs.utils.iterableplus.Iters;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkComponentMapping;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkImageViewCreateInfo;

import java.util.Set;

public class Image implements VulkanResource{
	
	public final  long     handle;
	public final  Extent2D extent;
	private final Device   device;
	
	public Image(long handle, Extent2D extent, Device device){
		this.handle = handle;
		this.extent = extent;
		this.device = device;
	}
	
	public ImageView createImageView(VkImageViewType type, VkFormat format, Set<VkImageAspectFlagBits> aspectFlags) throws VulkanCodeException{
		return createImageView(type, format, aspectFlags, 1, 1);
	}
	public ImageView createImageView(VkImageViewType type, VkFormat format, Set<VkImageAspectFlagBits> aspectFlags, int mipLevelCount, int layerCount) throws VulkanCodeException{
		try(var stack = MemoryStack.stackPush()){
			var info = VkImageViewCreateInfo.calloc(stack);
			info.sType$Default()
			    .image(handle)
			    .viewType(type.id)
			    .format(format.id)
			    .components(VkComponentMapping.malloc(stack).set(
				    VK10.VK_COMPONENT_SWIZZLE_IDENTITY,
				    VK10.VK_COMPONENT_SWIZZLE_IDENTITY,
				    VK10.VK_COMPONENT_SWIZZLE_IDENTITY,
				    VK10.VK_COMPONENT_SWIZZLE_IDENTITY
			    ))
			    .subresourceRange(VkImageSubresourceRange.malloc(stack).set(
				    Iters.from(aspectFlags).mapToInt(e -> e.bit).reduce(0, (a, b) -> a|b),
				    0,
				    mipLevelCount,
				    0,
				    layerCount
			    ));
			return device.createImageView(info);
		}
	}
	
	@Override
	public void destroy(){
		VK10.vkDestroyImage(device.value, handle, null);
	}
}
