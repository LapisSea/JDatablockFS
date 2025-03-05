package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.vk.CommandBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSubmitInfo;

import static com.lapissea.dfs.tools.newlogger.display.VUtils.check;

public class VulkanQueue implements VulkanResource{
	
	private final VkQueue   value;
	private final Swapchain swapchain;
	
	private final VulkanSemaphore presentCompleteSemaphore;
	private final VulkanSemaphore renderCompleteSemaphore;
	
	public VulkanQueue(Device device, Swapchain swapchain, QueueFamilyProps queue, int queueIndex){
		this.value = device.getQueue(queue, queueIndex);
		this.swapchain = swapchain;
		
		presentCompleteSemaphore = device.createSemaphore();
		renderCompleteSemaphore = device.createSemaphore();
	}
	
	
	public int acquireNextImage(){
		waitIdle();//TODO: BLOCKS EVERYTHING!!! FIX THIS PLEASE
		
		var index = new int[1];
		
		check(KHRSwapchain.vkAcquireNextImageKHR(
			value.getDevice(), swapchain.handle, Long.MAX_VALUE, presentCompleteSemaphore.handle, //Wait for presentation to complete
			0, index
		), "acquireNextImage");
		return index[0];
	}
	
	public void submitNow(CommandBuffer commandBuffer){
		try(var stack = MemoryStack.stackPush()){
			
			var info = VkSubmitInfo.calloc(stack).sType$Default()
			                       .pCommandBuffers(stack.pointers(commandBuffer.handle()));
			
			check(VK10.vkQueueSubmit(value, info, 0), "queueSubmit");
		}
	}
	public void submitAsync(CommandBuffer commandBuffer){
		try(var stack = MemoryStack.stackPush()){
			
			var info = VkSubmitInfo.malloc(stack)
			                       .sType$Default()
			                       .pNext(0)
			                       .waitSemaphoreCount(1)
			                       .pWaitSemaphores(stack.longs(presentCompleteSemaphore.handle))//Wait if presenting
			                       .pWaitDstStageMask(stack.ints(VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT))
			                       .pCommandBuffers(stack.pointers(commandBuffer.handle()))
			                       .pSignalSemaphores(stack.longs(renderCompleteSemaphore.handle));//Signal render complete when done
			
			check(VK10.vkQueueSubmit(value, info, 0), "queueSubmit");
		}
	}
	
	public void present(int index){
		try(var stack = MemoryStack.stackPush()){
			var info = VkPresentInfoKHR.calloc(stack).sType$Default()
			                           .pWaitSemaphores(stack.longs(renderCompleteSemaphore.handle))
			                           .swapchainCount(1)
			                           .pSwapchains(stack.longs(swapchain.handle))
			                           .pImageIndices(stack.ints(index));
			
			check(KHRSwapchain.vkQueuePresentKHR(value, info), "queuePresent");
		}
	}
	
	public void waitIdle(){
		VK10.vkQueueWaitIdle(value);
	}
	
	@Override
	public void destroy(){
		presentCompleteSemaphore.destroy();
		renderCompleteSemaphore.destroy();
	}
}
