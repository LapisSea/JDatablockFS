package com.lapissea.dfs.tools.newlogger.display;

import com.lapissea.dfs.tools.newlogger.display.vk.CommandBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanCore;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VKPresentMode;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.CommandPool;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Rect2D;
import com.lapissea.glfw.GlfwKeyboardEvent;
import com.lapissea.glfw.GlfwWindow;
import com.lapissea.util.UtilL;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkClearColorValue;

import java.awt.Color;
import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.lapissea.dfs.tools.newlogger.display.VUtils.createVulkanIcon;

public class VulkanDisplay implements AutoCloseable{
	
	private final GlfwWindow window;
	private final VulkanCore vkCore;
	
	private final CommandPool         cmdPool;
	private       List<CommandBuffer> graphicsBuffs;
	
	private final MsdfFontRender fontRender = new MsdfFontRender();
	
	private final ByteGridRender                byteGridRender = new ByteGridRender();
	private final ByteGridRender.RenderResource grid1Res       = new ByteGridRender.RenderResource();
	
	public VulkanDisplay(){
		
		window = createWindow();
		window.registryKeyboardKey.register(GLFW.GLFW_KEY_ESCAPE, GlfwKeyboardEvent.Type.DOWN, e -> window.requestClose());
		window.size.register(this::onResizeEvent);
		
		try{
			vkCore = new VulkanCore("DFS debugger", window, VKPresentMode.IMMEDIATE);
			
			fontRender.init(vkCore);
			byteGridRender.init(vkCore);
			
			cmdPool = vkCore.device.createCommandPool(vkCore.renderQueueFamily, CommandPool.Type.NORMAL);
			graphicsBuffs = cmdPool.createCommandBuffers(vkCore.swapchain.images.size());
			
		}catch(VulkanCodeException e){
			throw new RuntimeException("Failed to init vulkan display", e);
		}
		
	}
	
	private void render() throws VulkanCodeException{
		try{
			renderQueue();
		}catch(VulkanCodeException e){
			switch(e.code){
				case SUBOPTIMAL_KHR, ERROR_OUT_OF_DATE_KHR -> {
					handleResize();
				}
				default -> throw new RuntimeException("Failed to render frame", e);
			}
		}
	}
	
	private void renderQueue() throws VulkanCodeException{
		var swapchain = vkCore.swapchain;
		var queue     = vkCore.renderQueue;
		
		var frame = queue.nextFrame();
		
		queue.waitForFrameDone(frame);
		
		var index = queue.acquireNextImage(swapchain, frame);
		
		var buf = graphicsBuffs.get(frame);
		buf.reset();
		recordCommandBuffers(frame, index);
		
		queue.submitFrame(buf, frame);
		queue.present(swapchain, index, frame);
	}
	
	private boolean resizing;
	private Instant nextResizeWaitAttempt = Instant.now();
	private void handleResize() throws VulkanCodeException{
		resizing = true;
		try{
			vkCore.recreateSwapchainContext();
			if(vkCore.swapchain.images.size() != graphicsBuffs.size()){
				graphicsBuffs.forEach(CommandBuffer::destroy);
				graphicsBuffs = cmdPool.createCommandBuffers(vkCore.swapchain.images.size());
			}
			
			try{
				renderQueue();
			}catch(VulkanCodeException e2){
				switch(e2.code){
					case SUBOPTIMAL_KHR, ERROR_OUT_OF_DATE_KHR -> { }
					default -> throw new RuntimeException("Failed to render frame", e2);
				}
			}
		}finally{
			resizing = false;
		}
		
	}
	private void onResizeEvent(){
		var now = Instant.now();
		if(now.isBefore(nextResizeWaitAttempt)){
			return;
		}
		var end = now.plusMillis(50);
		if(!resizing){
			for(int i = 0; i<50; i++){
				if(resizing || Instant.now().isAfter(end)){
					break;
				}
				UtilL.sleep(0.5);
			}
		}
		for(int i = 0; i<100; i++){
			if(!resizing || Instant.now().isAfter(end)){
				break;
			}
			UtilL.sleep(0.5);
		}
		if(resizing){
			nextResizeWaitAttempt = Instant.now().plusMillis(1000);
		}
	}
	
	private void recordCommandBuffers(int frameID, int swapchainID) throws VulkanCodeException{
		
		try(var stack = MemoryStack.stackPush()){
			VkClearColorValue clearColor = VkClearColorValue.malloc(stack);
			
			var f = (float)Math.abs(Math.sin(System.currentTimeMillis()/1000D))*0.5F;
			clearColor.float32().put(0, new float[]{0, 0, 0, 1});
			
			var renderArea = new Rect2D(vkCore.swapchain.extent);
			
			var buf         = graphicsBuffs.get(frameID);
			var frameBuffer = vkCore.frameBuffers.get(swapchainID);
			
			buf.begin();
			try(var ignore = buf.beginRenderPass(vkCore.renderPass, frameBuffer, renderArea, clearColor)){
				
				List<MsdfFontRender.StringDraw> sd = new ArrayList<>();
				
				var pos = 0F;
				for(int i = 0; i<40; i++){
					float size = 1 + (i*i)*0.2F;
					
					var t = (System.currentTimeMillis())/500D;
					var h = (float)Math.sin(t + pos/(10 + i*3))*50;
					sd.add(new MsdfFontRender.StringDraw(size, Color.GREEN.darker(),
					                                     "a", 20 + pos, 70 + 360 - h));
					sd.add(new MsdfFontRender.StringDraw(size, Color.WHITE,
					                                     "a", 20 + pos, 70 + 360 - h, 1, 2F));
					pos += size*0.4F + 2;
				}
				sd.add(new MsdfFontRender.StringDraw(
					100, new Color(0.1F, 0.3F, 1, 1), "Hello world UwU", 100, 200));
				sd.add(new MsdfFontRender.StringDraw(
					100, new Color(1, 1, 1F, 0.5F), "Hello world UwU", 100, 200, 1, 1.5F));
				fontRender.render(buf, frameID, sd);
				
				byteGridRender.record(grid1Res);
				byteGridRender.render(buf, frameID, grid1Res);
			}
			buf.end();
			
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
		var t = Thread.ofPlatform().name("render").start(() -> {
			boolean first = true;
			while(!window.shouldClose()){
				try{
					render();
					if(first){
						first = false;
						vkCore.renderQueue.waitIdle();
						window.show();
					}
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
	public void close() throws VulkanCodeException{
		window.hide();
		
		vkCore.device.waitIdle();
		
		fontRender.destroy();
		
		grid1Res.destroy();
		byteGridRender.destroy();
		
		graphicsBuffs.forEach(CommandBuffer::destroy);
		cmdPool.destroy();
		
		vkCore.close();
		window.destroy();
	}
}
