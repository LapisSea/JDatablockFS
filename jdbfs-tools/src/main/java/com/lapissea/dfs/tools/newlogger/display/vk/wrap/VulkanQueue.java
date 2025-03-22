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
	
	private final VkQueue   value;
	private final Swapchain swapchain;
	
	private final VulkanSemaphore presentCompleteSemaphore;
	private final VulkanSemaphore renderCompleteSemaphore;
	
	public final Device           device;
	public final QueueFamilyProps familyProps;
	
	public VulkanQueue(Device device, Swapchain swapchain, QueueFamilyProps familyProps, int queueIndex) throws VulkanCodeException{
		this.device = device;
		this.familyProps = familyProps;
		this.swapchain = swapchain;
		
		this.value = device.getQueue(familyProps, queueIndex);
		
		presentCompleteSemaphore = swapchain == null? null : device.createSemaphore();
		try{
			renderCompleteSemaphore = swapchain == null? null : device.createSemaphore();
		}catch(VulkanCodeException e){
			presentCompleteSemaphore.destroy();
			throw e;
		}
	}
	
	public int acquireNextImage() throws VulkanCodeException{
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
	
	public void present(int index) throws VulkanCodeException{
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
		if(swapchain != null){
			presentCompleteSemaphore.destroy();
			renderCompleteSemaphore.destroy();
		}
	}
}
