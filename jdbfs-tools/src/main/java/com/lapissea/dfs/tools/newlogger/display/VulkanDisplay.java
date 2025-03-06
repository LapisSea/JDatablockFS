package com.lapissea.dfs.tools.newlogger.display;

import com.lapissea.dfs.tools.newlogger.display.vk.CommandBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanCore;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkAccessFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkImageLayout;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkPipelineStageFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkQueueCapability;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.CommandPool;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.MemoryBarrier;
import com.lapissea.glfw.GlfwKeyboardEvent;
import com.lapissea.glfw.GlfwWindow;
import com.lapissea.util.UtilL;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkImageSubresourceRange;

import java.io.File;
import java.util.List;
import java.util.Set;

import static com.lapissea.dfs.tools.newlogger.display.VUtils.createVulkanIcon;

public class VulkanDisplay implements AutoCloseable{
	
	private final GlfwWindow window;
	private final VulkanCore vkCore;
	
	private final CommandPool         cmdPool;
	private       List<CommandBuffer> graphicsBuffs;
	
	private boolean resizing;
	
	public VulkanDisplay(){
		window = createWindow();
		window.registryKeyboardKey.register(GLFW.GLFW_KEY_ESCAPE, GlfwKeyboardEvent.Type.DOWN, e -> window.requestClose());
		vkCore = new VulkanCore("DFS debugger", window);
		
		var family = vkCore.findQueueFamilyBy(VkQueueCapability.GRAPHICS).orElseThrow();
		
		cmdPool = vkCore.device.createCommandPool(family, CommandPool.Type.NORMAL);
		graphicsBuffs = cmdPool.createCommandBuffer(vkCore.swapchain.images.size());
		
		recordCommandBuffers();
		
		window.size.register(() -> {
			if(!resizing){
				for(int i = 0; i<32; i++){
					if(!resizing) UtilL.sleep(1);
				}
			}
			for(int i = 0; i<100; i++){
				if(resizing) UtilL.sleep(1);
			}
		});
	}
	
	private void render(){
		try{
			renderQueue();
		}catch(VulkanCodeException e){
			switch(e.code){
				case KHRSwapchain.VK_SUBOPTIMAL_KHR, KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR -> {
					handleResize();
				}
				default -> throw e;
			}
		}
	}
	private void renderQueue(){
		vkCore.renderQueue.waitIdle();
		recordCommandBuffers();
		var bufferQueue = vkCore.renderQueue;
		var index       = bufferQueue.acquireNextImage();
		bufferQueue.submitAsync(graphicsBuffs.get(index));
		bufferQueue.present(index);
	}
	
	private void handleResize(){
		resizing = true;
		try{
			vkCore.recreateSwapchain();
			if(vkCore.swapchain.images.size() != graphicsBuffs.size()){
				graphicsBuffs.forEach(CommandBuffer::destroy);
				graphicsBuffs = cmdPool.createCommandBuffer(vkCore.swapchain.images.size());
			}
			
			recordCommandBuffers();
			try{
				renderQueue();
			}catch(VulkanCodeException e2){
				switch(e2.code){
					case KHRSwapchain.VK_SUBOPTIMAL_KHR, KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR -> { }
					default -> throw e2;
				}
			}
			vkCore.renderQueue.waitIdle();
		}finally{
			resizing = false;
		}
		
	}
	
	private void recordCommandBuffers(){
		
		try(var stack = MemoryStack.stackPush()){
			VkClearColorValue clearColor = VkClearColorValue.malloc(stack);
			clearColor.float32().put(0, new float[]{0, 0, (float)Math.abs(Math.sin(System.currentTimeMillis()/1000D))*0.5F, 1});
			
			var imageRange = VkImageSubresourceRange.calloc(stack);
			imageRange.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
			          .baseMipLevel(0)
			          .levelCount(1)
			          .baseArrayLayer(0)
			          .layerCount(1);
			
			for(int i = 0; i<graphicsBuffs.size(); i++){
				var buf    = graphicsBuffs.get(i);
				var target = vkCore.swapchain.images.get(i);
				
				var presentToClear = new MemoryBarrier.BarImage(
					VkAccessFlag.MEMORY_READ,
					VkAccessFlag.TRANSFER_WRITE,
					VkImageLayout.UNDEFINED,
					VkImageLayout.TRANSFER_DST_OPTIMAL,
					target,
					imageRange
				);
				var clearToPresent = new MemoryBarrier.BarImage(
					presentToClear,
					VkAccessFlag.MEMORY_READ,
					VkImageLayout.PRESENT_SRC_KHR
				);
				
				buf.begin(Set.of());
				{
					buf.pipelineBarrier(VkPipelineStageFlag.TRANSFER, VkPipelineStageFlag.TRANSFER, 0, List.of(presentToClear));
					
					buf.clearColorImage(target, presentToClear.newLayout(), clearColor, imageRange);
					
					buf.pipelineBarrier(VkPipelineStageFlag.TRANSFER, VkPipelineStageFlag.BOTTOM_OF_PIPE, 0, List.of(clearToPresent));
				}
				buf.end();
			}

//			LogUtil.println("BUFFERS RECORDED!!");
		}
		
	}
	
	private GlfwWindow createWindow(){
		var win = new GlfwWindow();
		win.title.set("DFS visual debugger");
		win.size.set(800, 600);
		win.centerWindow();
		
		var winFile = new File("winRemember.json");
		win.loadState(winFile);
		win.autoHandleStateSaving(winFile);
		
		win.init(i -> i.withVulkan(v -> v.withVersion(VulkanCore.API_VERSION_MAJOR, VulkanCore.API_VERSION_MINOR)).resizeable(true));
		Thread.ofVirtual().start(() -> win.setIcon(createVulkanIcon(128, 128)));
		return win;
	}
	
	public void run(){
		window.show();
		
		var t = Thread.ofPlatform().name("render").start(() -> {
			while(!window.shouldClose()){
				try{
					render();
				}catch(Throwable e){
					e.printStackTrace();
					window.requestClose();
				}
			}
		});
		while(!window.shouldClose()){
			try{
				window.pollEvents();
			}catch(Throwable e){
				e.printStackTrace();
				window.requestClose();
			}
			try{
				Thread.sleep(5);
			}catch(InterruptedException e){ window.requestClose(); }
		}
		try{
			t.join();
		}catch(InterruptedException e){
			throw new RuntimeException(e);
		}
	}
	
	@Override
	public void close(){
		window.hide();
		
		vkCore.renderQueue.waitIdle();
		
		for(var buf : graphicsBuffs){
			buf.destroy();
		}
		cmdPool.destroy();
		
		vkCore.close();
		window.destroy();
	}
	
}
