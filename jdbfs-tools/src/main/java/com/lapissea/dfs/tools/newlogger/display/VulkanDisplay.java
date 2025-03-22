package com.lapissea.dfs.tools.newlogger.display;

import com.lapissea.dfs.tools.newlogger.display.vk.BufferAndMemory;
import com.lapissea.dfs.tools.newlogger.display.vk.CommandBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.GraphicsPipeline;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanCore;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanTexture;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VKPresentMode;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.CommandPool;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Rect2D;
import com.lapissea.glfw.GlfwKeyboardEvent;
import com.lapissea.glfw.GlfwWindow;
import com.lapissea.util.UtilL;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkClearColorValue;

import java.io.File;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static com.lapissea.dfs.tools.newlogger.display.VUtils.createVulkanIcon;

public class VulkanDisplay implements AutoCloseable{
	
	private final GlfwWindow window;
	private final VulkanCore vkCore;
	
	private final CommandPool         cmdPool;
	private       List<CommandBuffer> graphicsBuffs;
	
	public BufferAndMemory  verts;
	public GraphicsPipeline gPipeline;
	
	private List<BufferAndMemory> uniformBuffs;
	
	private final CompletableFuture<VulkanTexture> texture = VulkanTexture.loadTexture("roboto/light/mask.png", true, this::blockingCore);
	
	public static final class Vert{
		public static final int SIZE = 4*(2 + 3 + 2);
		
		public static void put(ByteBuffer data, float x, float y, float r, float g, float b, float u, float v){
			data.putFloat(x).putFloat(y)
			    .putFloat(r).putFloat(g).putFloat(b)
			    .putFloat(u).putFloat(v);
		}
	}
	
	public VulkanDisplay(){
		window = createWindow();
		window.registryKeyboardKey.register(GLFW.GLFW_KEY_ESCAPE, GlfwKeyboardEvent.Type.DOWN, e -> window.requestClose());
		window.size.register(this::onResizeEvent);
		
		try{
			vkCore = new VulkanCore("DFS debugger", window, VKPresentMode.IMMEDIATE);
			
			cmdPool = vkCore.device.createCommandPool(vkCore.renderQueueFamily, CommandPool.Type.NORMAL);
			graphicsBuffs = cmdPool.createCommandBuffers(vkCore.swapchain.images.size());
			
			verts = vkCore.createVertexBuffer(6*Vert.SIZE, bb -> {
				Vert.put(bb, 0, 1F, 1, 0, 0, 0, 1);
				Vert.put(bb, 1F, 1F, 0, 1, 0, 1, 1);
				Vert.put(bb, 0, 0, 0, 0, 1, 0, 0);
				
				Vert.put(bb, 0, 0, 0, 0, 1, 0, 0);
				Vert.put(bb, 1F, 1F, 0, 1, 0, 1, 1);
				Vert.put(bb, 1, 0F, 1, 1, 0, 1, 0);
			});
			
			createPipeline();
			
			recordCommandBuffers();
		}catch(VulkanCodeException e){
			throw new RuntimeException("Failed to init vulkan display", e);
		}
		
	}
	
	private VulkanCore blockingCore(){
		UtilL.sleepWhile(() -> vkCore == null);
		return vkCore;
	}
	
	private void updateUniforms(int index) throws VulkanCodeException{
		var buf = uniformBuffs.get(index);
		buf.update(b -> {
			var f = b.asFloatBuffer();
			
			var t   = System.currentTimeMillis()%100000;
			var mat = new Matrix4f();
			mat.translate(220, 355, 0);
			mat.rotate((float)(t/1000D%(Math.PI*2)), new Vector3f(0, 0, 1));
			mat.scale((float)(Math.sin(t/800D)/2.1 + 0.5));
			
			mat.scale(1000);
			mat.translate(-0.5F, -0.5F, 0);
			
			var s = vkCore.swapchain.extent;
			mat.get(f);
			mat.identity();
			mat.ortho(0, s.width, 0, s.height, -10, 10, true);
			mat.get(f.position(16));
		});
	}
	
	private void createPipeline() throws VulkanCodeException{
		assert uniformBuffs == null;
		
		var matSize = 4*4;
		var size    = matSize*2;
		var fSize   = size*Float.SIZE;
		
		uniformBuffs = vkCore.allocateUniformBuffers(fSize);
		gPipeline = vkCore.createPipeline(verts.buffer, uniformBuffs, texture.join());
	}
	private void destroyPipeline(){
		gPipeline.destroy();
		uniformBuffs.forEach(BufferAndMemory::destroy);
		uniformBuffs = null;
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
		
		updateUniforms(index);
		
		queue.submitFrame(graphicsBuffs.get(index), frame);
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
			
			destroyPipeline();
			createPipeline();
			
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
				
				buf.begin();
				try(var ignore = buf.beginRenderPass(vkCore.renderPass, frameBuffer, renderArea, clearColor)){
					
					buf.bindPipeline(gPipeline, i);
					
					buf.draw(6, 1, 0, 0);
					
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
	public void close(){
		window.hide();
		
		try{
			vkCore.renderQueue.waitIdle();
		}catch(VulkanCodeException e){ e.printStackTrace(); }
		
		destroyPipeline();
		
		verts.destroy();
		texture.join().destroy();
		
		for(var buf : graphicsBuffs){
			buf.destroy();
		}
		cmdPool.destroy();
		
		vkCore.close();
		window.destroy();
	}
}
