package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.CommandBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.VKCalls;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSubmitInfo;

public class VulkanQueue implements VulkanResource{
	
	private final VkQueue value;
	
	private VulkanSemaphore presentCompleteSemaphore;
	private VulkanSemaphore renderCompleteSemaphore;
	
	public final Device device;
	
	public final QueueFamilyProps queueFamily;
	
	public VulkanQueue(Device device, QueueFamilyProps queueFamily, VkQueue value){
		this.device = device;
		this.queueFamily = queueFamily;
		this.value = value;
	}
	private void ensureSemaphores() throws VulkanCodeException{
		if(presentCompleteSemaphore == null){
			createSemaphores();
		}
	}
	private void createSemaphores() throws VulkanCodeException{
		presentCompleteSemaphore = device.createSemaphore();
		renderCompleteSemaphore = device.createSemaphore();
	}
	
	public int acquireNextImage(Swapchain swapchain) throws VulkanCodeException{
		ensureSemaphores();
		waitIdle();//TODO: BLOCKS EVERYTHING!!! FIX THIS PLEASE
		
		return VKCalls.vkAcquireNextImageKHR(
			value.getDevice(), swapchain, Long.MAX_VALUE, presentCompleteSemaphore, //Wait for presentation to complete
			0
		);
	}
	
	public void submitNow(CommandBuffer commandBuffer) throws VulkanCodeException{
		try(var stack = MemoryStack.stackPush()){
			
			var info = VkSubmitInfo.calloc(stack).sType$Default()
			                       .pCommandBuffers(stack.pointers(commandBuffer.handle()));
			VKCalls.vkQueueSubmit(value, info, 0);
		}
		waitIdle();
	}
	
	public void submitAsync(CommandBuffer commandBuffer) throws VulkanCodeException{
		ensureSemaphores();
		try(var stack = MemoryStack.stackPush()){
			
			var info = VkSubmitInfo.malloc(stack)
			                       .sType$Default()
			                       .pNext(0)
			                       .waitSemaphoreCount(1)
			                       .pWaitSemaphores(stack.longs(presentCompleteSemaphore.handle))//Wait if presenting
			                       .pWaitDstStageMask(stack.ints(VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT))
			                       .pCommandBuffers(stack.pointers(commandBuffer.handle()))
			                       .pSignalSemaphores(stack.longs(renderCompleteSemaphore.handle));//Signal render complete when done
			
			VKCalls.vkQueueSubmit(value, info, 0);
		}
	}
	
	public void present(Swapchain swapchain, int index) throws VulkanCodeException{
		ensureSemaphores();
		try(var stack = MemoryStack.stackPush()){
			var info = VkPresentInfoKHR.calloc(stack).sType$Default()
			                           .pWaitSemaphores(stack.longs(renderCompleteSemaphore.handle))
			                           .swapchainCount(1)
			                           .pSwapchains(stack.longs(swapchain.handle))
			                           .pImageIndices(stack.ints(index));
			
			VKCalls.vkQueuePresentKHR(value, info);
		}
	}
	
	public void waitIdle() throws VulkanCodeException{
		VKCalls.vkQueueWaitIdle(value);
	}
	
	@Override
	public void destroy(){
		try{
			waitIdle();
		}catch(VulkanCodeException e){ e.printStackTrace(); }
		
		if(presentCompleteSemaphore != null) presentCompleteSemaphore.destroy();
		if(renderCompleteSemaphore != null) renderCompleteSemaphore.destroy();
		
	}
}
