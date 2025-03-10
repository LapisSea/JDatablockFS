package com.lapissea.dfs.tools.newlogger.display;

import com.lapissea.dfs.tools.newlogger.display.vk.CommandBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanCore;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkQueueCapability;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.CommandPool;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Rect2D;
import com.lapissea.glfw.GlfwKeyboardEvent;
import com.lapissea.glfw.GlfwWindow;
import com.lapissea.util.UtilL;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkClearColorValue;

import java.io.File;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static com.lapissea.dfs.tools.newlogger.display.VUtils.createVulkanIcon;

public class VulkanDisplay implements AutoCloseable{
	
	private final GlfwWindow window;
	private final VulkanCore vkCore;
	
	private final CommandPool         cmdPool;
	private       List<CommandBuffer> graphicsBuffs;
	
	static class Vert{
		private static final int  SIZE = 8;
		private final        long ptr;
		
		Vert(ByteBuffer data, float x, float y){
			this(data);
			x(x).y(y);
		}
		Vert(ByteBuffer data){
			ptr = MemoryUtil.memAddress(data);
			assert data.remaining()>=SIZE;
		}
		
		public Vert x(float x){
			MemoryUtil.memPutFloat(ptr, x);
			return this;
		}
		public Vert y(float y){
			MemoryUtil.memPutFloat(ptr + 4, y);
			return this;
		}
		public float x(){ return MemoryUtil.memGetFloat(ptr); }
		public float y(){ return MemoryUtil.memGetFloat(ptr + 4); }
		
		public static void put(ByteBuffer data, float x, float y){
			var ptr = MemoryUtil.memAddress(data);
			data.position(data.position() + 8);
			MemoryUtil.memPutFloat(ptr, x);
			MemoryUtil.memPutFloat(ptr + 4, y);
		}
	}
	
	public VulkanDisplay(){
		window = createWindow();
		window.registryKeyboardKey.register(GLFW.GLFW_KEY_ESCAPE, GlfwKeyboardEvent.Type.DOWN, e -> window.requestClose());
		window.size.register(this::onResizeEvent);
		
		try{
			vkCore = new VulkanCore("DFS debugger", window);
			
			var family = vkCore.findQueueFamilyBy(VkQueueCapability.GRAPHICS).orElseThrow();
			
			cmdPool = vkCore.device.createCommandPool(family, CommandPool.Type.NORMAL);
			graphicsBuffs = cmdPool.createCommandBuffers(vkCore.swapchain.images.size());
			
			var bb = MemoryUtil.memAlloc(3*Vert.SIZE);
			Vert.put(bb, -0.7F, 0.7F);
			Vert.put(bb, 0.7F, 0.7F);
			Vert.put(bb, 0, -0.7F);
			bb.flip();
			
			var buff = vkCore.createVertexBuffer(bb);
			MemoryUtil.memFree(bb);
			
			buff.destroy();
			
			recordCommandBuffers();
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
//		vkCore.renderQueue.waitIdle();
//		recordCommandBuffers();
		var bufferQueue = vkCore.renderQueue;
		var index       = bufferQueue.acquireNextImage();
		bufferQueue.submitAsync(graphicsBuffs.get(index));
		bufferQueue.present(index);
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
			
			recordCommandBuffers();
			try{
				renderQueue();
			}catch(VulkanCodeException e2){
				switch(e2.code){
					case SUBOPTIMAL_KHR, ERROR_OUT_OF_DATE_KHR -> { }
					default -> throw new RuntimeException("Failed to render frame", e2);
				}
			}
			vkCore.renderQueue.waitIdle();
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
	
	private void recordCommandBuffers() throws VulkanCodeException{
		
		try(var stack = MemoryStack.stackPush()){
			VkClearColorValue clearColor = VkClearColorValue.malloc(stack);
			
			var f = (float)Math.abs(Math.sin(System.currentTimeMillis()/1000D))*0.5F;
			clearColor.float32().put(0, new float[]{0, 0, 0, 1});
			
			var renderArea = new Rect2D(vkCore.swapchain.extent);
			
			for(int i = 0; i<graphicsBuffs.size(); i++){
				var buf         = graphicsBuffs.get(i);
				var frameBuffer = vkCore.frameBuffers.get(i);
				
				buf.begin(Set.of());
				try(var ignore = buf.beginRenderPass(vkCore.renderPass, frameBuffer, renderArea, clearColor)){
					
					buf.bindPipeline(vkCore.pipeline, true);
					
					buf.draw(3, 1, 0, 0);
					
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
		
		try{
			vkCore.renderQueue.waitIdle();
		}catch(VulkanCodeException e){ e.printStackTrace(); }
		
		for(var buf : graphicsBuffs){
			buf.destroy();
		}
		cmdPool.destroy();
		
		vkCore.close();
		window.destroy();
	}
}
