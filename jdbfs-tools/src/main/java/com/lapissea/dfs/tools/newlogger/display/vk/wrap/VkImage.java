package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.CommandBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.Flags;
import com.lapissea.dfs.tools.newlogger.display.vk.VKCalls;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkCommandBufferUsageFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkFilter;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkFormat;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkImageAspectFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkImageLayout;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkImageViewType;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkSampleCountFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkSamplerAddressMode;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkComponentMapping;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

public class VkImage implements VulkanResource{
	
	public final long   handle;
	public final Device device;
	
	public final Extent3D          extent;
	public final VkFormat          format;
	public final VkSampleCountFlag samples;
	
	public VkImage(long handle, Device device, VkImageCreateInfo info){
		this(handle, device,
		     new Extent3D(info.extent()),
		     VkFormat.from(info.format()),
		     VkSampleCountFlag.from(info.samples()).asOne()
		);
	}
	public VkImage(long handle, Device device, Extent3D extent, VkFormat format, VkSampleCountFlag samples){
		this.handle = handle;
		this.device = device;
		this.extent = extent;
		this.format = format;
		this.samples = samples;
	}
	
	public MemoryRequirements getRequirements(){
		try(var stack = MemoryStack.stackPush()){
			var res = VkMemoryRequirements.malloc(stack);
			VK10.vkGetImageMemoryRequirements(device.value, handle, res);
			return new MemoryRequirements(res.size(), res.alignment(), res.memoryTypeBits());
		}
	}
	
	public VkImageView createImageView(VkImageViewType type, VkFormat format, Flags<VkImageAspectFlag> aspectFlags) throws VulkanCodeException{
		return createImageView(type, format, aspectFlags, 1, 1);
	}
	public VkImageView createImageView(VkImageViewType type, VkFormat format, Flags<VkImageAspectFlag> aspectFlags, int mipLevelCount, int layerCount) throws VulkanCodeException{
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
				    aspectFlags.value,
				    0,
				    mipLevelCount,
				    0,
				    layerCount
			    ));
			return device.createImageView(info);
		}
	}
	public VkSampler createSampler(VkFilter min, VkFilter max, VkSamplerAddressMode samplerAddressMode) throws VulkanCodeException{
		try(var stack = MemoryStack.stackPush()){
			var info = VkSamplerCreateInfo.calloc(stack);
			info.sType$Default()
			    .magFilter(min.id)
			    .minFilter(max.id)
			    .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_LINEAR)
			    .addressModeU(samplerAddressMode.id)
			    .addressModeV(samplerAddressMode.id)
			    .addressModeW(samplerAddressMode.id)
			    .mipLodBias(0)
			    .anisotropyEnable(false)
			    .maxAnisotropy(1)
			    .compareEnable(false)
			    .compareOp(VK10.VK_COMPARE_OP_ALWAYS)
			    .minLod(0)
			    .maxLod(0)
			    .borderColor(VK10.VK_BORDER_COLOR_FLOAT_OPAQUE_BLACK)
			    .unnormalizedCoordinates(false);
			
			return VKCalls.vkCreateSampler(device, info);
		}
	}
	
	public void transitionLayout(VulkanQueue queue, CommandBuffer cmd, VkImageLayout oldLayout, VkImageLayout newLayout) throws VulkanCodeException{
		cmd.begin(VkCommandBufferUsageFlag.ONE_TIME_SUBMIT_BIT);
		
		cmd.imageMemoryBarrier(this, oldLayout, newLayout);
		
		cmd.end();
		
		queue.submitNow(cmd);
		queue.waitIdle();
	}
	public void copyBufferToImage(VulkanQueue queue, CommandBuffer cmd, VkBuffer buffer) throws VulkanCodeException{
		cmd.begin(VkCommandBufferUsageFlag.ONE_TIME_SUBMIT_BIT);
		
		try(var stack = MemoryStack.stackPush()){
			var info = VkBufferImageCopy.calloc(stack);
			info.bufferOffset(0)
			    .bufferRowLength(0)
			    .bufferImageHeight(0)
			    .imageSubresource(s -> s.set(VkImageAspectFlag.COLOR.bit, 0, 0, 1))
			    .imageOffset(e -> e.set(0, 0, 0))
			    .imageExtent(extent.toStack(stack));
			
			cmd.copyBufferToImage(buffer, this, VkImageLayout.TRANSFER_DST_OPTIMAL, info);
		}
		
		cmd.end();
		
		queue.submitNow(cmd);
		queue.waitIdle();
	}
	
	@Override
	public void destroy(){
		VK10.vkDestroyImage(device.value, handle, null);
	}
}
