package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkAccessFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkImageLayout;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferMemoryBarrier;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkMemoryBarrier;

public sealed interface MemoryBarrier{
	
	record BarGlobal(
		int srcAccessMask,
		int dstAccessMask
	) implements MemoryBarrier{
		public void set(VkMemoryBarrier.Buffer dest){
			dest.sType$Default()
			    .pNext(0)
			    .srcAccessMask(srcAccessMask)
			    .dstAccessMask(dstAccessMask);
		}
	}
	
	record BarBuffer(
		VkAccessFlag srcAccessMask,
		VkAccessFlag dstAccessMask,
		int srcQueueFamilyIndex,
		int dstQueueFamilyIndex,
		VkBuffer buffer,
		long offset,
		long size
	) implements MemoryBarrier{
		public BarBuffer(VkAccessFlag srcAccessMask, VkAccessFlag dstAccessMask, VkBuffer buffer){
			this(srcAccessMask, dstAccessMask, buffer, 0, VK10.VK_WHOLE_SIZE);
		}
		public BarBuffer(VkAccessFlag srcAccessMask, VkAccessFlag dstAccessMask, VkBuffer buffer, long offset, long size){
			this(srcAccessMask, dstAccessMask, VK10.VK_QUEUE_FAMILY_IGNORED, VK10.VK_QUEUE_FAMILY_IGNORED, buffer, offset, size);
		}
		public void set(VkBufferMemoryBarrier.Buffer dest){
			dest.sType$Default()
			    .pNext(0)
			    .srcAccessMask(srcAccessMask.bit)
			    .dstAccessMask(dstAccessMask.bit)
			    .srcQueueFamilyIndex(srcQueueFamilyIndex)
			    .dstQueueFamilyIndex(dstQueueFamilyIndex)
			    .buffer(buffer.handle)
			    .offset(offset)
			    .size(size);
		}
	}
	
	record BarImage(
		VkAccessFlag srcAccessMask,
		VkAccessFlag dstAccessMask,
		VkImageLayout oldLayout,
		VkImageLayout newLayout,
		int srcQueueFamilyIndex,
		int dstQueueFamilyIndex,
		VkImage image,
		VkImageSubresourceRange subresourceRange
	) implements MemoryBarrier{
		public BarImage(BarImage previous, VkAccessFlag dstAccessMask, VkImageLayout newLayout){
			this(previous.dstAccessMask, dstAccessMask, previous.newLayout, newLayout, VK10.VK_QUEUE_FAMILY_IGNORED, VK10.VK_QUEUE_FAMILY_IGNORED, previous.image, previous.subresourceRange);
		}
		public BarImage(VkAccessFlag srcAccessMask, VkAccessFlag dstAccessMask, VkImageLayout oldLayout, VkImageLayout newLayout, VkImage image, VkImageSubresourceRange subresourceRange){
			this(srcAccessMask, dstAccessMask, oldLayout, newLayout, VK10.VK_QUEUE_FAMILY_IGNORED, VK10.VK_QUEUE_FAMILY_IGNORED, image, subresourceRange);
		}
		public void set(VkImageMemoryBarrier.Buffer dest){
			dest.sType$Default()
			    .pNext(0)
			    .srcAccessMask(srcAccessMask.bit)
			    .dstAccessMask(dstAccessMask.bit)
			    .oldLayout(oldLayout.id)
			    .newLayout(newLayout.id)
			    .srcQueueFamilyIndex(srcQueueFamilyIndex)
			    .dstQueueFamilyIndex(dstQueueFamilyIndex)
			    .image(image.handle)
			    .subresourceRange(subresourceRange);
		}
	}
	
}
