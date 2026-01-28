package com.lapissea.dfs.inspect.display.vk.wrap;

import com.lapissea.dfs.inspect.display.VulkanCodeException;
import com.lapissea.dfs.inspect.display.VulkanWindow;
import com.lapissea.dfs.inspect.display.vk.CommandBuffer;
import com.lapissea.dfs.inspect.display.vk.VKCalls;
import com.lapissea.dfs.inspect.display.vk.VulkanResource;
import com.lapissea.dfs.inspect.display.vk.enums.VkResult;
import com.lapissea.dfs.utils.iterableplus.Iters;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSubmitInfo;

import java.util.List;

import static com.lapissea.dfs.inspect.display.vk.VulkanCore.MAX_IN_FLIGHT_FRAMES;

public class VulkanQueue implements VulkanResource{
	
	public static final class SwapSync extends VulkanQueue{
		
		private VkFence[]     inFlightRender;
		private VkSemaphore[] presentComplete;
		private VkSemaphore[] renderComplete;
		
		private int frameID;
		
		private final int imageCount;
		
		private SwapSync(Device device, QueueFamilyProps queueFamily, VkQueue value, int imageCount) throws VulkanCodeException{
			super(device, queueFamily, value);
			this.imageCount = imageCount;
			
			inFlightRender = device.createFences(MAX_IN_FLIGHT_FRAMES, true);
			presentComplete = device.createSemaphores(MAX_IN_FLIGHT_FRAMES);
			renderComplete = device.createSemaphores(imageCount);
		}
		
		public int nextFrame(){
			return frameID = (frameID + 1)%MAX_IN_FLIGHT_FRAMES;
		}
		
		public void waitForFrameDone(int frameID) throws VulkanCodeException{
			inFlightRender[frameID].waitReset();
		}
		
		/**
		 * @return next swapID determined by the swapchain
		 *
		 */
		public int acquireNextImage(Swapchain swapchain, int frameID) throws VulkanCodeException{
			return VKCalls.vkAcquireNextImageKHR(
				value.getDevice(), swapchain, Long.MAX_VALUE, presentComplete[frameID], //Wait for presentation to complete
				null
			);
		}
		
		public void submitFrame(CommandBuffer commandBuffer, int frameID, int swapID) throws VulkanCodeException{
			try(var stack = MemoryStack.stackPush()){
				
				var info = VkSubmitInfo.malloc(stack);
				info.sType$Default()
				    .pNext(0)
				    .waitSemaphoreCount(1)
				    .pWaitSemaphores(stack.longs(presentComplete[frameID].handle))//Wait if presenting
				    .pWaitDstStageMask(stack.ints(VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT))
				    .pCommandBuffers(stack.pointers(commandBuffer.handle()))
				    .pSignalSemaphores(stack.longs(renderComplete[swapID].handle));//Signal render complete when done
				
				VKCalls.vkQueueSubmit(value, info, inFlightRender[frameID]);
			}
		}
		
		/**
		 * An object that represents a ready frame that is to be presented by the queue
		 */
		public record PresentFrame(VulkanWindow window, int swapID, VkSemaphore renderCompleteSemaphore){ }
		
		public PresentFrame makePresentFrame(VulkanWindow window, int swapID){
			return new PresentFrame(window, swapID, renderComplete[swapID]);
		}
		/**
		 * @return if there was a swapchain recreation
		 */
		public boolean present(List<PresentFrame> presentFrames) throws VulkanCodeException{
			try(var stack = MemoryStack.stackPush()){
				
				var semaphores = stack.mallocLong(presentFrames.size());
				var swapchains = stack.mallocLong(presentFrames.size());
				var indices    = stack.mallocInt(presentFrames.size());
				var results    = stack.mallocInt(presentFrames.size());
				
				var info = VkPresentInfoKHR.calloc(stack);
				info.sType$Default()
				    .pWaitSemaphores(semaphores)
				    .swapchainCount(presentFrames.size())
				    .pSwapchains(swapchains)
				    .pImageIndices(indices)
				    .pResults(results);
				
				for(var f : presentFrames){
					semaphores.put(f.renderCompleteSemaphore.handle);
					swapchains.put(f.window.swapchain.handle);
					indices.put(f.swapID);
				}
				try{
					VKCalls.vkQueuePresentKHR(value, info);
					return false;
				}catch(VulkanCodeException e){
					//Recover swapchains that need a recreation
					var errs = Iters.ofInts(results).mapToObj(VkResult::from);
					for(var ent : errs.enumerate().filter(v -> v.val() != VkResult.SUCCESS)){
						var err    = ent.val();
						var window = presentFrames.get(ent.index()).window;
						
						if(err == VkResult.SUBOPTIMAL_KHR || err == VkResult.ERROR_OUT_OF_DATE_KHR){
							device.waitIdle();
							window.recreateSwapchainContext();
							continue;
						}
						throw new VulkanCodeException(
							"vkQueuePresentKHR: " +
							errs.enumerate((i, v) -> "  " + presentFrames.get(i).window + " : " + v).joinAsStr("\n"),
							err
						);
					}
					return true;
				}
			}
		}
		
		public void resetSync() throws VulkanCodeException{
			waitIdle();
			for(int i = 0; i<MAX_IN_FLIGHT_FRAMES; i++){
				inFlightRender[i].destroy();
				presentComplete[i].destroy();
			}
			for(var sem : renderComplete){
				sem.destroy();
			}
			inFlightRender = device.createFences(MAX_IN_FLIGHT_FRAMES, true);
			presentComplete = device.createSemaphores(MAX_IN_FLIGHT_FRAMES);
			renderComplete = device.createSemaphores(imageCount);
		}
		
		@Override
		public void destroy(){
			super.destroy();
			for(int i = 0; i<MAX_IN_FLIGHT_FRAMES; i++){
				inFlightRender[i].destroy();
				presentComplete[i].destroy();
			}
			for(var sem : renderComplete){
				sem.destroy();
			}
		}
	}
	
	protected final VkQueue value;
	
	public final Device           device;
	public final QueueFamilyProps queueFamily;
	
	public VulkanQueue(VulkanQueue old){ this(old.device, old.queueFamily, old.value); }
	public VulkanQueue(Device device, QueueFamilyProps queueFamily, VkQueue value){
		this.device = device;
		this.queueFamily = queueFamily;
		this.value = value;
	}
	
	public void submitNow(CommandBuffer commandBuffer) throws VulkanCodeException{
		try(var fence = device.createFence(false)){
			submit(commandBuffer, fence);
			fence.waitFor();
		}catch(Throwable e){
			device.waitIdle();
			throw e;
		}
	}
	public void submit(CommandBuffer commandBuffer, VkFence fence) throws VulkanCodeException{
		try(var stack = MemoryStack.stackPush()){
			var info = VkSubmitInfo.calloc(stack)
			                       .sType$Default()
			                       .pCommandBuffers(stack.pointers(commandBuffer.handle()));
			VKCalls.vkQueueSubmit(value, info, fence);
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
	}
	
	public SwapSync withSwap(int imageCount) throws VulkanCodeException{
		return new SwapSync(device, queueFamily, value, imageCount);
	}
}
