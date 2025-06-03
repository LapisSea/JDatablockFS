package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.VUtils;
import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.Flags;
import com.lapissea.dfs.tools.newlogger.display.vk.TransferBuffers;
import com.lapissea.dfs.tools.newlogger.display.vk.VKCalls;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VKImageType;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkAccessFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkFilter;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkFormat;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkImageAspectFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkImageLayout;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkImageViewType;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkMemoryPropertyFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkPipelineStageFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkSampleCountFlag;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkComponentMapping;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;

import java.nio.ByteBuffer;
import java.util.List;

public class VkImage extends VulkanResource.DeviceHandleObj{
	
	
	public final Extent3D          extent;
	public final VkFormat          format;
	public final VkSampleCountFlag samples;
	public final VKImageType       type;
	
	public VkImage(Device device, long handle, VkImageCreateInfo info){
		this(device, handle,
		     new Extent3D(info.extent()),
		     VkFormat.from(info.format()),
		     VkSampleCountFlag.from(info.samples()).asOne(),
		     VKImageType.from(info.imageType())
		);
	}
	public VkImage(Device device, long handle, Extent3D extent, VkFormat format, VkSampleCountFlag samples, VKImageType type){
		super(device, handle);
		this.extent = extent;
		this.format = format;
		this.samples = samples;
		this.type = type;
	}
	
	public VkDeviceMemory allocateAndBindRequiredMemory(PhysicalDevice physicalDevice, VkMemoryPropertyFlag requiredProperty) throws VulkanCodeException{
		return allocateAndBindRequiredMemory(physicalDevice, Flags.of(requiredProperty));
	}
	public VkDeviceMemory allocateAndBindRequiredMemory(PhysicalDevice physicalDevice, Flags<VkMemoryPropertyFlag> requiredProperties) throws VulkanCodeException{
		var requirements    = getRequirements();
		var memoryTypeIndex = physicalDevice.getMemoryTypeIndex(requirements.memoryTypeBits(), requiredProperties);
		
		var mem = device.allocateMemory(requirements.size(), memoryTypeIndex);
		try{
			VKCalls.vkBindImageMemory(this, mem, 0);
		}catch(Throwable e){
			mem.destroy();
			throw e;
		}
		return mem;
	}
	
	public MemoryRequirements getRequirements(){
		try(var stack = MemoryStack.stackPush()){
			var res = VkMemoryRequirements.malloc(stack);
			VK10.vkGetImageMemoryRequirements(device.value, handle, res);
			return new MemoryRequirements(res.size(), res.alignment(), res.memoryTypeBits());
		}
	}
	
	public VkImageView createImageView(VkImageViewType type, VkFormat format, VkImageAspectFlag aspectFlag) throws VulkanCodeException{
		return createImageView(type, format, Flags.of(aspectFlag));
	}
	public VkImageView createImageView(VkImageViewType type, VkFormat format, Flags<VkImageAspectFlag> aspectFlags) throws VulkanCodeException{
		return createImageView(type, format, aspectFlags, 1, 1);
	}
	public VkImageView createImageView(VkImageViewType type, VkFormat format, VkImageAspectFlag aspectFlag, int mipLevelCount, int layerCount) throws VulkanCodeException{
		return createImageView(type, format, Flags.of(aspectFlag), mipLevelCount, layerCount);
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
	
	public void transitionLayout(TransferBuffers transferBuffers, VkImageLayout oldLayout, VkImageLayout newLayout, int mipLevels) throws VulkanCodeException{
		transferBuffers.syncAction(cmd -> {
			cmd.imageMemoryBarrier(this, oldLayout, newLayout, mipLevels);
		});
	}
	public void copyBufferToImage(TransferBuffers transferBuffers, VkBuffer buffer) throws VulkanCodeException{
		transferBuffers.syncAction(cmd -> {
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
		});
	}
	
	
	public void updateContents(TransferBuffers transferBuffers, TransferBuffers transientGraphicsBuffs, ByteBuffer pixels, int mipLevels) throws VulkanCodeException{
		int size = VUtils.getBytesPerPixel(format)*extent.depth*extent.width*extent.height;
		
		if(pixels.remaining() != size) throw new AssertionError(pixels.remaining() + " != " + size);
		try(var imgMem = device.allocateStagingBuffer(size)){
			imgMem.update(b -> b.put(pixels));
			
			transitionLayout(transferBuffers, VkImageLayout.UNDEFINED, VkImageLayout.TRANSFER_DST_OPTIMAL, mipLevels);
			copyBufferToImage(transferBuffers, imgMem.buffer);
			if(mipLevels>1){
				generateMips(transientGraphicsBuffs, mipLevels);
			}else{
				transitionLayout(transientGraphicsBuffs, VkImageLayout.TRANSFER_DST_OPTIMAL, VkImageLayout.SHADER_READ_ONLY_OPTIMAL, mipLevels);
			}
		}
	}
	
	private void generateMips(TransferBuffers transientGraphicsBuffs, int mipLevels) throws VulkanCodeException{
		transientGraphicsBuffs.syncAction(buf -> {
			try(var stack = MemoryStack.stackPush()){
				var subresource = VkImageSubresourceRange.malloc(stack);
				subresource.aspectMask(VkImageAspectFlag.COLOR.bit)
				           .baseArrayLayer(0)
				           .layerCount(1)
				           .levelCount(1);
				
				
				var blits = VkImageBlit.calloc(1, stack);
				var blit  = blits.get(0);
				
				int mipWidth  = extent.width;
				int mipHeight = extent.height;
				
				for(int i0 = 1; i0<mipLevels; i0++){
					var i = i0;
					subresource.baseMipLevel(i - 1);
					var imgBar = new MemoryBarrier.BarImage(
						VkAccessFlag.TRANSFER_WRITE,
						VkAccessFlag.TRANSFER_READ,
						VkImageLayout.TRANSFER_DST_OPTIMAL,
						VkImageLayout.TRANSFER_SRC_OPTIMAL,
						this,
						subresource
					);
					buf.pipelineBarrier(VkPipelineStageFlag.TRANSFER, VkPipelineStageFlag.TRANSFER, 0, List.of(imgBar));
					
					blit.srcOffsets(0).set(0, 0, 0);
					blit.srcOffsets(1).set(mipWidth, mipHeight, 1);
					blit.srcSubresource(r -> r.aspectMask(VkImageAspectFlag.COLOR.bit)
					                          .mipLevel(i - 1)
					                          .baseArrayLayer(0)
					                          .layerCount(1));
					
					mipWidth = Math.max(1, mipWidth/2);
					mipHeight = Math.max(1, mipHeight/2);
					
					blit.dstOffsets(0).set(0, 0, 0);
					blit.dstOffsets(1).set(mipWidth, mipHeight, 1);
					blit.dstSubresource(r -> r.aspectMask(VkImageAspectFlag.COLOR.bit)
					                          .mipLevel(i)
					                          .baseArrayLayer(0)
					                          .layerCount(1));
					
					buf.blitImage(this, VkImageLayout.TRANSFER_SRC_OPTIMAL,
					              this, VkImageLayout.TRANSFER_DST_OPTIMAL,
					              blits, VkFilter.LINEAR);
					
					buf.pipelineBarrier(VkPipelineStageFlag.TRANSFER, VkPipelineStageFlag.FRAGMENT_SHADER, 0, List.of(
						new MemoryBarrier.BarImage(
							imgBar,
							VkAccessFlag.SHADER_READ,
							VkImageLayout.SHADER_READ_ONLY_OPTIMAL
						)
					));
				}
				subresource.baseMipLevel(mipLevels - 1);
				buf.pipelineBarrier(VkPipelineStageFlag.TRANSFER, VkPipelineStageFlag.FRAGMENT_SHADER, 0, List.of(new MemoryBarrier.BarImage(
					VkAccessFlag.TRANSFER_WRITE,
					VkAccessFlag.SHADER_READ,
					VkImageLayout.TRANSFER_DST_OPTIMAL,
					VkImageLayout.SHADER_READ_ONLY_OPTIMAL,
					this,
					subresource
				)));
			}
			
		});
	}
	
	@Override
	public void destroy(){
		VK10.vkDestroyImage(device.value, handle, null);
	}
}
