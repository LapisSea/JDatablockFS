package com.lapissea.dfs.tools.newlogger.display.vk;

import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkAccessFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkCommandBufferUsageFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkFilter;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkFormat;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkImageAspectFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkImageLayout;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkPipelineBindPoint;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkPipelineStageFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.CommandPool;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.FrameBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.MemoryBarrier;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Rect2D;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.RenderPass;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkDescriptorSet;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkImage;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkPipeline;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkPipelineLayout;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkBufferMemoryBarrier;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkDrawIndirectCommand;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderPassBeginInfo;
import org.lwjgl.vulkan.VkViewport;

import java.util.List;

public class CommandBuffer implements VulkanResource{
	
	private final VkCommandBuffer val;
	private final CommandPool     pool;
	
	public CommandBuffer(long handle, CommandPool pool){
		this.val = new VkCommandBuffer(handle, pool.device.value);
		this.pool = pool;
	}
	
	public void begin(VkCommandBufferUsageFlag... usage) throws VulkanCodeException{
		begin(Flags.of(usage));
	}
	public void begin(Flags<VkCommandBufferUsageFlag> usage) throws VulkanCodeException{
		try(var stack = MemoryStack.stackPush()){
			var info = VkCommandBufferBeginInfo.calloc(stack).sType$Default()
			                                   .flags(usage.value);
			VKCalls.vkBeginCommandBuffer(val, info);
		}
	}
	public void end() throws VulkanCodeException{
		VKCalls.vkEndCommandBuffer(val);
	}
	
	public void reset() throws VulkanCodeException{
		VKCalls.vkResetCommandBuffer(val, 0);
	}
	
	public long handle(){
		return val.address();
	}
	
	@Override
	public void destroy(){
		VKCalls.vkFreeCommandBuffers(pool, val);
	}
	
	public void clearColorImage(VkImage image, VkImageLayout layout, VkClearColorValue color, VkImageSubresourceRange range){
		VK10.vkCmdClearColorImage(val, image.handle, layout.id, color, range);
	}
	
	public void setViewportScissor(Rect2D rect){
		setViewport(rect);
		setScissor(rect);
	}
	public void setViewport(Rect2D viewport){
		try(var stack = MemoryStack.stackPush()){
			var viewports = VkViewport.calloc(1, stack);
			viewport.setViewport(viewports.get(0));
			setViewport(viewports);
		}
	}
	public void setScissor(Rect2D scissor){
		try(var stack = MemoryStack.stackPush()){
			var scissors = VkRect2D.calloc(1, stack);
			scissor.set(scissors.get(0));
			setScissor(scissors);
		}
	}
	public void setViewport(VkViewport.Buffer viewports){
		VK10.vkCmdSetViewport(val, 0, viewports);
	}
	public void setScissor(VkRect2D.Buffer scissors){
		VK10.vkCmdSetScissor(val, 0, scissors);
	}
	public void pipelineBarrier(VkPipelineStageFlag srcStageMask, VkPipelineStageFlag dstStageMask, int dependencyFlags, List<MemoryBarrier> barriers){
		int gc = 0;
		int mc = 0;
		int ic = 0;
		for(var barrier : barriers){
			if(barrier instanceof MemoryBarrier.BarGlobal) gc++;
			else if(barrier instanceof MemoryBarrier.BarBuffer) mc++;
			else ic++;
		}
		
		try(var stack = MemoryStack.stackPush()){
			VkMemoryBarrier.Buffer globals = null;
			if(gc>0){
				globals = VkMemoryBarrier.malloc(gc, stack);
				int i = 0;
				for(var barrier : barriers){
					if(!(barrier instanceof MemoryBarrier.BarGlobal bar)){
						continue;
					}
					bar.set(globals.position(i++));
				}
				globals.position(0);
			}
			VkBufferMemoryBarrier.Buffer memories = null;
			if(mc>0){
				memories = VkBufferMemoryBarrier.malloc(mc, stack);
				int i = 0;
				for(var barrier : barriers){
					if(!(barrier instanceof MemoryBarrier.BarBuffer bar)){
						continue;
					}
					bar.set(memories.position(i++));
				}
				memories.position(0);
			}
			VkImageMemoryBarrier.Buffer images = null;
			if(ic>0){
				images = VkImageMemoryBarrier.malloc(ic, stack);
				int i = 0;
				for(var barrier : barriers){
					if(!(barrier instanceof MemoryBarrier.BarImage bar)){
						continue;
					}
					bar.set(images.position(i++));
				}
				images.position(0);
			}
			
			VK10.vkCmdPipelineBarrier(val, srcStageMask.bit, dstStageMask.bit, dependencyFlags, globals, memories, images);
		}
	}
	
	public interface RenderPassScope extends AutoCloseable{
		@Override
		void close();
	}
	
	public RenderPassScope beginRenderPass(RenderPass renderPass, FrameBuffer frameBuffer, Rect2D renderArea, VkClearColorValue color){
		try(var stack = MemoryStack.stackPush()){
			
			var info = VkRenderPassBeginInfo.malloc(stack)
			                                .sType$Default().pNext(0)
			                                .renderPass(renderPass.handle)
			                                .framebuffer(frameBuffer.handle)
			                                .renderArea(renderArea.set(VkRect2D.malloc(stack)))
			                                .pClearValues(VkClearValue.malloc(2, stack).color(color).position(1).color(color).position(0));
			
			VK10.vkCmdBeginRenderPass(val, info, VK10.VK_SUBPASS_CONTENTS_INLINE);
			
			return () -> VK10.vkCmdEndRenderPass(val);
		}
	}
	
	public void bindPipeline(VkPipeline pipeline, boolean graphics){
		VK10.vkCmdBindPipeline(val, graphics? VK10.VK_PIPELINE_BIND_POINT_GRAPHICS : VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline.handle);
	}
	
	public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance){
		VK10.vkCmdDraw(val, vertexCount, instanceCount, firstVertex, firstInstance);
	}
	public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance){
		VK10.vkCmdDrawIndexed(val, indexCount, instanceCount, firstIndex, vertexOffset, firstInstance);
	}
	
	public void drawIndirect(IndirectDrawBuffer buffer, int frameID, int drawCount){
		VK10.vkCmdDrawIndirect(val, buffer.getBuffer(frameID).handle, 0, drawCount, VkDrawIndirectCommand.SIZEOF);
	}
	public void drawIndirect(VkBuffer buffer, long offset, int drawCount, int stride){
		VK10.vkCmdDrawIndirect(val, buffer.handle, offset, drawCount, stride);
	}
	public void drawIndirectIndexed(VkBuffer buffer, long offset, int drawCount, int stride){
		VK10.vkCmdDrawIndexedIndirect(val, buffer.handle, offset, drawCount, stride);
	}
	
	
	public void copyBuffer(VkBuffer srcBuff, VkBuffer dstBuff, long size){
		copyBuffer(srcBuff, 0, dstBuff, 0, size);
	}
	public void copyBuffer(VkBuffer srcBuff, long srcOffset, VkBuffer dstBuff, long dstOffset, long size){
		try(var stack = MemoryStack.stackPush()){
			
			var copyInfo = VkBufferCopy.malloc(1, stack);
			copyInfo.srcOffset(srcOffset)
			        .dstOffset(dstOffset)
			        .size(size);
			
			copyBuffer(srcBuff, dstBuff, copyInfo);
		}
	}
	public void copyBuffer(VkBuffer srcBuff, VkBuffer dstBuff, VkBufferCopy.Buffer regions){
		VK10.vkCmdCopyBuffer(val, srcBuff.handle, dstBuff.handle, regions);
	}
	
	public void imageMemoryBarrier(VkImage image, VkImageLayout oldLayout, VkImageLayout newLayout, int mipLevels){
		try(var stack = MemoryStack.stackPush()){
			var barriers = VkImageMemoryBarrier.calloc(1, stack);
			var barrier  = barriers.get(0);
			barrier.sType$Default()
			       .srcAccessMask(0)
			       .dstAccessMask(0)
			       .oldLayout(oldLayout.id)
			       .newLayout(newLayout.id)
			       .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
			       .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
			       .image(image.handle);
			
			var subresourceRange = barrier.subresourceRange();
			subresourceRange.set(VkImageAspectFlag.COLOR.bit, 0, mipLevels, 0, 1);
			
			VkPipelineStageFlag sourceStage      = VkPipelineStageFlag.NONE;
			VkPipelineStageFlag destinationStage = VkPipelineStageFlag.NONE;
			
			var format = image.format;
			
			var srcAccessMask = barrier.srcAccessMask();
			var dstAccessMask = barrier.dstAccessMask();
			
			if(newLayout == VkImageLayout.DEPTH_STENCIL_ATTACHMENT_OPTIMAL ||
			   (format == VkFormat.D16_UNORM) ||
			   (format == VkFormat.X8_D24_UNORM_PACK32) ||
			   (format == VkFormat.D32_SFLOAT) ||
			   (format == VkFormat.S8_UINT) ||
			   (format == VkFormat.D16_UNORM_S8_UINT) ||
			   (format == VkFormat.D24_UNORM_S8_UINT)){
				subresourceRange.aspectMask(VkImageAspectFlag.DEPTH.bit);
				
				if(format == VkFormat.D32_SFLOAT_S8_UINT || format == VkFormat.D24_UNORM_S8_UINT){
					subresourceRange.aspectMask(subresourceRange.aspectMask()|VkImageAspectFlag.STENCIL.bit);
				}
			}else{
				subresourceRange.aspectMask(VkImageAspectFlag.COLOR.bit);
			}
			
			if(oldLayout == VkImageLayout.UNDEFINED && newLayout == VkImageLayout.SHADER_READ_ONLY_OPTIMAL){
				srcAccessMask = 0;
				dstAccessMask = VkAccessFlag.SHADER_READ.bit;
				
				sourceStage = VkPipelineStageFlag.TOP_OF_PIPE;
				destinationStage = VkPipelineStageFlag.FRAGMENT_SHADER;
			}else if(oldLayout == VkImageLayout.UNDEFINED && newLayout == VkImageLayout.GENERAL){
				srcAccessMask = 0;
				dstAccessMask = VkAccessFlag.SHADER_READ.bit;
				
				sourceStage = VkPipelineStageFlag.TRANSFER;
				destinationStage = VkPipelineStageFlag.FRAGMENT_SHADER;
			}
			
			if(oldLayout == VkImageLayout.UNDEFINED &&
			   newLayout == VkImageLayout.TRANSFER_DST_OPTIMAL){
				srcAccessMask = 0;
				dstAccessMask = VkAccessFlag.TRANSFER_WRITE.bit;
				
				sourceStage = VkPipelineStageFlag.TOP_OF_PIPE;
				destinationStage = VkPipelineStageFlag.TRANSFER;
			}
			/* Convert back from read-only to updateable */
			else if(oldLayout == VkImageLayout.SHADER_READ_ONLY_OPTIMAL && newLayout == VkImageLayout.TRANSFER_DST_OPTIMAL){
				srcAccessMask = VkAccessFlag.SHADER_READ.bit;
				dstAccessMask = VkAccessFlag.TRANSFER_WRITE.bit;
				
				sourceStage = VkPipelineStageFlag.FRAGMENT_SHADER;
				destinationStage = VkPipelineStageFlag.TRANSFER;
			}
			/* Convert from updateable texture to shader read-only */
			else if(oldLayout == VkImageLayout.TRANSFER_DST_OPTIMAL &&
			        newLayout == VkImageLayout.SHADER_READ_ONLY_OPTIMAL){
				srcAccessMask = VkAccessFlag.TRANSFER_WRITE.bit;
				dstAccessMask = VkAccessFlag.SHADER_READ.bit;
				
				sourceStage = VkPipelineStageFlag.TRANSFER;
				destinationStage = VkPipelineStageFlag.FRAGMENT_SHADER;
			}
			/* Convert depth texture from undefined state to depth-stencil buffer */
			else if(oldLayout == VkImageLayout.UNDEFINED && newLayout == VkImageLayout.DEPTH_STENCIL_ATTACHMENT_OPTIMAL){
				srcAccessMask = 0;
				dstAccessMask = VkAccessFlag.DEPTH_STENCIL_ATTACHMENT_READ.bit|VkAccessFlag.DEPTH_STENCIL_ATTACHMENT_WRITE.bit;
				
				sourceStage = VkPipelineStageFlag.TOP_OF_PIPE;
				destinationStage = VkPipelineStageFlag.EARLY_FRAGMENT_TESTS;
			}
			/* Wait for render pass to complete */
			else if(oldLayout == VkImageLayout.SHADER_READ_ONLY_OPTIMAL && newLayout == VkImageLayout.SHADER_READ_ONLY_OPTIMAL){
				srcAccessMask = 0; // VkAccessFlag.SHADER_READ;
				dstAccessMask = 0;
		/*
				sourceStage = VkPipelineStageFlag.FRAGMENT_SHADER;
		///		destinationStage = VkPipelineStageFlag.ALL_GRAPHICS;
				destinationStage = VkPipelineStageFlag.COLOR_ATTACHMENT_OUTPUT;
		*/
				sourceStage = VkPipelineStageFlag.COLOR_ATTACHMENT_OUTPUT;
				destinationStage = VkPipelineStageFlag.FRAGMENT_SHADER;
			}
			/* Convert back from read-only to color attachment */
			else if(oldLayout == VkImageLayout.SHADER_READ_ONLY_OPTIMAL && newLayout == VkImageLayout.COLOR_ATTACHMENT_OPTIMAL){
				srcAccessMask = VkAccessFlag.SHADER_READ.bit;
				dstAccessMask = VkAccessFlag.COLOR_ATTACHMENT_WRITE.bit;
				
				sourceStage = VkPipelineStageFlag.FRAGMENT_SHADER;
				destinationStage = VkPipelineStageFlag.COLOR_ATTACHMENT_OUTPUT;
			}
			/* Convert from updateable texture to shader read-only */
			else if(oldLayout == VkImageLayout.COLOR_ATTACHMENT_OPTIMAL && newLayout == VkImageLayout.SHADER_READ_ONLY_OPTIMAL){
				srcAccessMask = VkAccessFlag.COLOR_ATTACHMENT_WRITE.bit;
				dstAccessMask = VkAccessFlag.SHADER_READ.bit;
				
				sourceStage = VkPipelineStageFlag.COLOR_ATTACHMENT_OUTPUT;
				destinationStage = VkPipelineStageFlag.FRAGMENT_SHADER;
			}
			/* Convert back from read-only to depth attachment */
			else if(oldLayout == VkImageLayout.SHADER_READ_ONLY_OPTIMAL && newLayout == VkImageLayout.DEPTH_STENCIL_ATTACHMENT_OPTIMAL){
				srcAccessMask = VkAccessFlag.SHADER_READ.bit;
				dstAccessMask = VkAccessFlag.DEPTH_STENCIL_ATTACHMENT_WRITE.bit;
				
				sourceStage = VkPipelineStageFlag.FRAGMENT_SHADER;
				destinationStage = VkPipelineStageFlag.LATE_FRAGMENT_TESTS;
			}
			/* Convert from updateable depth texture to shader read-only */
			else if(oldLayout == VkImageLayout.DEPTH_STENCIL_ATTACHMENT_OPTIMAL && newLayout == VkImageLayout.SHADER_READ_ONLY_OPTIMAL){
				srcAccessMask = VkAccessFlag.DEPTH_STENCIL_ATTACHMENT_WRITE.bit;
				dstAccessMask = VkAccessFlag.SHADER_READ.bit;
				
				sourceStage = VkPipelineStageFlag.LATE_FRAGMENT_TESTS;
				destinationStage = VkPipelineStageFlag.FRAGMENT_SHADER;
			}
			
			barrier.srcAccessMask(srcAccessMask);
			barrier.dstAccessMask(dstAccessMask);
			
			VK10.vkCmdPipelineBarrier(
				val, sourceStage.bit, destinationStage.bit, 0, null, null, barriers
			);
			
		}
	}
	public void copyBufferToImage(VkBuffer src, VkImage dest, VkImageLayout imageLayout, VkBufferImageCopy info){
		VK10.vkCmdCopyBufferToImage(val, src.handle, dest.handle, imageLayout.id, new VkBufferImageCopy.Buffer(info.address(), 1));
	}
	
	public void blitImage(VkImage src, VkImageLayout srcImageLayout, VkImage dst, VkImageLayout dstImageLayout, VkImageBlit.Buffer regions, VkFilter filter){
		VK10.vkCmdBlitImage(val, src.handle, srcImageLayout.id, dst.handle, dstImageLayout.id, regions, filter.id);
	}
	
	public void bindDescriptorSet(VkPipelineBindPoint bindPoint, VkPipelineLayout layout, int firstSet, VkDescriptorSet descriptorSet){
		VK10.vkCmdBindDescriptorSets(val, bindPoint.id, layout.handle, firstSet, new long[]{descriptorSet.handle}, null);
	}
	public void bindDescriptorSets(VkPipelineBindPoint bindPoint, VkPipelineLayout layout, int firstSet, List<VkDescriptorSet> descriptorSets){
		var setHandles = new long[descriptorSets.size()];
		for(int i = 0; i<setHandles.length; i++){
			setHandles[i] = descriptorSets.get(i).handle;
		}
		VK10.vkCmdBindDescriptorSets(val, bindPoint.id, layout.handle, firstSet, setHandles, null);
	}
	
	
}
