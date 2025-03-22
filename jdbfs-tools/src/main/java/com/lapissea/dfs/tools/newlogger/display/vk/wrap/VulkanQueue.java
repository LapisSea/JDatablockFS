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

import static com.lapissea.dfs.tools.newlogger.display.vk.VulkanCore.MAX_IN_FLIGHT_FRAMES;

public class VulkanQueue implements VulkanResource{
	
	private final VkQueue value;
	
	private final VkSemaphore[] presentCompleteSemaphore;
	private final VkSemaphore[] renderCompleteSemaphore;
	
	public final Device device;
	
	public final QueueFamilyProps queueFamily;
	
	public VulkanQueue(Device device, QueueFamilyProps queueFamily, VkQueue value){
		this.device = device;
		this.queueFamily = queueFamily;
		this.value = value;
		try{
			presentCompleteSemaphore = device.createSemaphores(MAX_IN_FLIGHT_FRAMES);
			renderCompleteSemaphore = device.createSemaphores(MAX_IN_FLIGHT_FRAMES);
		}catch(Throwable e){
			throw new RuntimeException(e);
		}
	}
	
	public int acquireNextImage(Swapchain swapchain, int frame) throws VulkanCodeException{
		return VKCalls.vkAcquireNextImageKHR(
			value.getDevice(), swapchain, Long.MAX_VALUE, presentCompleteSemaphore[frame], //Wait for presentation to complete
			null
		);
	}
	
	public void submitNow(CommandBuffer commandBuffer) throws VulkanCodeException{
		try(var stack = MemoryStack.stackPush();
		    var fence = device.createFence(false)){
			
			var info = VkSubmitInfo.calloc(stack)
			                       .sType$Default()
			                       .pCommandBuffers(stack.pointers(commandBuffer.handle()));
			VKCalls.vkQueueSubmit(value, info, fence);
			fence.waitFor();
		}
	}
	
	public void submitAsync(CommandBuffer commandBuffer, VkFence fence, int frame) throws VulkanCodeException{
		try(var stack = MemoryStack.stackPush()){
			
			var info = VkSubmitInfo.malloc(stack)
			                       .sType$Default()
			                       .pNext(0)
			                       .waitSemaphoreCount(1)
			                       .pWaitSemaphores(stack.longs(presentCompleteSemaphore[frame].handle))//Wait if presenting
			                       .pWaitDstStageMask(stack.ints(VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT))
			                       .pCommandBuffers(stack.pointers(commandBuffer.handle()))
			                       .pSignalSemaphores(stack.longs(renderCompleteSemaphore[frame].handle));//Signal render complete when done
			
			VKCalls.vkQueueSubmit(value, info, fence);
		}
	}
	
	public void present(Swapchain swapchain, int index, int frame) throws VulkanCodeException{
		try(var stack = MemoryStack.stackPush()){
			var info = VkPresentInfoKHR.calloc(stack)
			                           .sType$Default()
			                           .pWaitSemaphores(stack.longs(renderCompleteSemaphore[frame].handle))
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
		
		for(var s : presentCompleteSemaphore) s.destroy();
		for(var s : renderCompleteSemaphore) s.destroy();
		
	}
}
