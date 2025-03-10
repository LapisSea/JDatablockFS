package com.lapissea.dfs.tools.newlogger.display.vk;

import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkCommandBufferUsageFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkImageLayout;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkPipelineStageFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.CommandPool;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.FrameBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Image;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.MemoryBarrier;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Pipeline;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Rect2D;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.RenderPass;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkBuffer;
import com.lapissea.dfs.utils.iterableplus.Iters;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferMemoryBarrier;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderPassBeginInfo;

import java.util.List;
import java.util.Set;

public class CommandBuffer implements VulkanResource{
	
	private final VkCommandBuffer val;
	private final CommandPool     pool;
	
	public CommandBuffer(long handle, CommandPool pool){
		this.val = new VkCommandBuffer(handle, pool.device.value);
		this.pool = pool;
	}
	
	public void begin(Set<VkCommandBufferUsageFlag> usage) throws VulkanCodeException{
		try(var stack = MemoryStack.stackPush()){
			var info = VkCommandBufferBeginInfo.calloc(stack).sType$Default()
			                                   .flags(Iters.from(usage).mapToInt(e -> e.bit).reduce(0, (a, b) -> a|b));
			VKCalls.vkBeginCommandBuffer(val, info);
		}
	}
	public void end() throws VulkanCodeException{
		VKCalls.vkEndCommandBuffer(val);
	}
	
	public long handle(){
		return val.address();
	}
	
	@Override
	public void destroy(){
		VKCalls.vkFreeCommandBuffers(pool, val);
	}
	
	public void clearColorImage(Image image, VkImageLayout layout, VkClearColorValue color, VkImageSubresourceRange range){
		VK10.vkCmdClearColorImage(val, image.handle, layout.id, color, range);
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
			                                .pClearValues(VkClearValue.malloc(1, stack).color(color));
			
			VK10.vkCmdBeginRenderPass(val, info, VK10.VK_SUBPASS_CONTENTS_INLINE);
			
			return () -> VK10.vkCmdEndRenderPass(val);
		}
	}
	
	public void bindPipeline(Pipeline pipeline, boolean graphics){
		VK10.vkCmdBindPipeline(val, graphics? VK10.VK_PIPELINE_BIND_POINT_GRAPHICS : VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline.handle);
	}
	public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance){
		VK10.vkCmdDraw(val, vertexCount, instanceCount, firstVertex, firstInstance);
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
}
