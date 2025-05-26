package com.lapissea.dfs.tools.newlogger.display;

import com.lapissea.dfs.tools.newlogger.display.imgui.ImTools;
import com.lapissea.dfs.tools.newlogger.display.renderers.ByteGridRender;
import com.lapissea.dfs.tools.newlogger.display.renderers.Geometry;
import com.lapissea.dfs.tools.newlogger.display.renderers.ImGUIRenderer;
import com.lapissea.dfs.tools.newlogger.display.renderers.LineRenderer;
import com.lapissea.dfs.tools.newlogger.display.renderers.MsdfFontRender;
import com.lapissea.dfs.tools.newlogger.display.vk.CommandBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanCore;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VKPresentMode;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.CommandPool;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Rect2D;
import com.lapissea.dfs.utils.RawRandom;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.glfw.GlfwKeyboardEvent;
import com.lapissea.glfw.GlfwWindow;
import com.lapissea.util.UtilL;
import imgui.ImGui;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector4f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkClearColorValue;

import java.awt.Color;
import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import static com.lapissea.dfs.tools.newlogger.display.VUtils.createVulkanIcon;
import static org.lwjgl.glfw.GLFW.glfwGetTime;

public class VulkanDisplay implements AutoCloseable{
	
	static{
		Thread.ofVirtual().start(ImGui::init);
		Thread.ofVirtual().start(GlfwWindow::initGLFW);
	}
	
	private final VulkanCore core;
	
	private final CommandPool         cmdPool;
	private       List<CommandBuffer> graphicsBuffs;
	
	private final MsdfFontRender fontRender;
	
	private final ByteGridRender                byteGridRender;
	private final ByteGridRender.RenderResource grid1Res = new ByteGridRender.RenderResource();
	
	private final LineRenderer                lineRenderer;
	private final LineRenderer.RenderResource lineRes = new LineRenderer.RenderResource();
	
	private final ImGUIRenderer imGUIRenderer;
	
	private final Vector4f clearColor = new Vector4f(0, 0, 0, 1);
	
	private record ImGUIKeyEvent(int code, boolean pressed){
		int imguiCode(){
			return ImTools.glfwKeyToImGuiKey(code);
		}
	}
	
	private final ConcurrentLinkedQueue<ImGUIKeyEvent> keyboardEvents = new ConcurrentLinkedQueue<>();
	
	public final VulkanWindow window;
	
	public VulkanDisplay(){
		try{
			ImGui.setCurrentContext(ImGui.createContext());
			core = new VulkanCore("DFS debugger", VKPresentMode.IMMEDIATE);
			
			window = createMainWindow();
			
			fontRender = new MsdfFontRender(core);
			byteGridRender = new ByteGridRender(core);
			lineRenderer = new LineRenderer(core);
			imGUIRenderer = new ImGUIRenderer(core);
			
			cmdPool = core.device.createCommandPool(core.renderQueueFamily, CommandPool.Type.NORMAL);
			graphicsBuffs = cmdPool.createCommandBuffers(window.swapchain.images.size());
			
			byte[] bytes = new RawRandom(10).nextBytes(32*32);
			
			byteGridRender.record(
				grid1Res,
				new Matrix4f().translate(30, 30, 0).scale(15),
				32,
				bytes,
				List.of(
					new ByteGridRender.DrawRange(0, bytes.length/2, Color.green.darker()),
					new ByteGridRender.DrawRange(bytes.length/2, bytes.length, Color.RED.darker())
				),
				List.of(
					new ByteGridRender.IOEvent(6, 10, ByteGridRender.IOEvent.Type.WRITE),
					new ByteGridRender.IOEvent(8, 20, ByteGridRender.IOEvent.Type.READ)
				)
			);
			
			lineRenderer.record(lineRes, new Matrix4f(), List.of(
				new Geometry.PointsLine(
					Iters.rangeMap(0, 50, u -> u/50F*Math.PI)
					     .map(f -> new Vector2f((float)Math.sin(f)*100 + 150, -(float)Math.cos(f)*100 + 150))
					     .toList(),
					5, Color.ORANGE
				)
			));
		}catch(VulkanCodeException e){
			throw new RuntimeException("Failed to init vulkan display", e);
		}
		
	}
	
	private VulkanWindow createMainWindow() throws VulkanCodeException{
		final VulkanWindow window;
		window = new VulkanWindow(core);
		
		var win = window.getGlfwWindow();
		win.registryKeyboardKey.register(GLFW.GLFW_KEY_ESCAPE, GlfwKeyboardEvent.Type.DOWN, e -> window.requestClose());
		
		var winFile = new File("winRemember.json");
		win.loadState(winFile);
		win.autoHandleStateSaving(winFile);
		
		win.size.register(this::onResizeEvent);
		return window;
	}
	
	private void render() throws VulkanCodeException{
		if(window.swapchain == null){
			UtilL.sleep(50);
			handleResize();
			return;
		}
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
		var swapchain = window.swapchain;
		var queue     = core.renderQueue;
		
		var frame = queue.nextFrame();
		
		queue.waitForFrameDone(frame);
		
		var index = queue.acquireNextImage(swapchain, frame);
		
		var buf = graphicsBuffs.get(frame);
		buf.reset();
		imguiUpdate();
		recordCommandBuffers(frame, index);
		
		queue.submitFrame(buf, frame);
		queue.present(swapchain, index, frame);
	}
	
	private double time;
	
	private void imguiUpdate(){
		var io     = ImGui.getIO();
		var window = this.window.getGlfwWindow();
		var mouse  = window.mousePos;
		
		var extent = this.window.swapchain.extent;
		io.setDisplaySize(extent.width, extent.height);
		
		final double currentTime = glfwGetTime();
		io.setDeltaTime(time>0.0? (float)(currentTime - time) : 1.0f/60.0f);
		time = currentTime;
		
		io.setMousePos(mouse.x(), mouse.y());
		
		io.setMouseDown(0, window.isMouseKeyDown(GLFW.GLFW_MOUSE_BUTTON_LEFT));
		io.setMouseDown(1, window.isMouseKeyDown(GLFW.GLFW_MOUSE_BUTTON_RIGHT));
		io.setMouseDown(2, window.isMouseKeyDown(GLFW.GLFW_MOUSE_BUTTON_MIDDLE));
		
		ImGUIKeyEvent event;
		while((event = keyboardEvents.poll()) != null){
			try{
				io.addKeyEvent(event.imguiCode(), event.pressed);
			}catch(Throwable e){
				new RuntimeException(event.toString(), e).printStackTrace();
			}
		}
	}
	
	private boolean resizing;
	private Instant nextResizeWaitAttempt = Instant.now();
	private void handleResize() throws VulkanCodeException{
		resizing = true;
		try{
			window.recreateSwapchainContext();
			if(window.swapchain == null){
				return;
			}
			if(window.swapchain.images.size() != graphicsBuffs.size()){
				graphicsBuffs.forEach(CommandBuffer::destroy);
				graphicsBuffs = cmdPool.createCommandBuffers(window.swapchain.images.size());
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
	
	private int[] selectedImageIndexBuf = {0};
	private void recordCommandBuffers(int frameID, int swapchainID) throws VulkanCodeException{
		
		try(var stack = MemoryStack.stackPush()){
			VkClearColorValue clearColor = VkClearColorValue.malloc(stack);
			this.clearColor.get(clearColor.float32());
			
			var renderArea = new Rect2D(window.swapchain.extent);
			
			var buf         = graphicsBuffs.get(frameID);
			var frameBuffer = window.frameBuffers.get(swapchainID);
			
			buf.begin();
			try(var ignore = buf.beginRenderPass(core.renderPass, frameBuffer, renderArea, clearColor)){
				
				renderAutoSizeByteGrid(frameID, buf);
				
				List<MsdfFontRender.StringDraw> sd = new ArrayList<>();
				
				//testFontWave(sd);
				
				sd.add(new MsdfFontRender.StringDraw(
					100, new Color(0.1F, 0.3F, 1, 1), "Hello world UwU", 100, 200));
				sd.add(new MsdfFontRender.StringDraw(
					100, new Color(1, 1, 1F, 0.5F), "Hello world UwU", 100, 200, 1, 1.5F));
				fontRender.render(window, buf, frameID, sd);
				
				
				renderDecimatedCurve(frameID, buf);
				
				ImGui.newFrame();
				ImGui.dockSpaceOverViewport();
				
				ImGui.dockSpace(1);
				if(ImGui.begin("Main")){
					ImGui.text("Hello from Java + Vulkan!");
					ImGui.sliderInt("##Frame slider", selectedImageIndexBuf, 0, 255);
					ImGui.text("Frame: " + selectedImageIndexBuf[0]);
				}
				ImGui.end();
				
				if(ImGui.begin("Binary view")){
					ImGui.textColored(0xFF0000FF, "TODO");
//					ImGui.image(69, ImGui.getContentRegionAvailX(), ImGui.getContentRegionAvailY());
				}
				ImGui.end();
				
				if(ImGui.begin("Reference tree view")){
					ImGui.textColored(0xFF0000FF, "TODO");
//					ImGui.image(69, ImGui.getContentRegionAvailX(), ImGui.getContentRegionAvailY());
				}
				ImGui.end();
				
				ImGui.render();

//				imGUIRenderer.submit(buf, frameID, ImGui.getDrawData());
			}
			buf.end();
		}
		
	}
	
	private void renderDecimatedCurve(int frameID, CommandBuffer buf) throws VulkanCodeException{
		var t = (System.currentTimeMillis())/500D;
		
		var controlPoints = Iters.of(3D, 2D, 1D, 4D, 5D).enumerate((i, s) -> new Vector2f(
			(float)Math.sin(t/s)*100 + 200*(i + 1),
			(float)Math.cos(t/s)*100 + 200
		)).toList();
		
		lineRenderer.record(lineRes, new Matrix4f(), Iters.concat1N(
			new Geometry.BezierCurve(controlPoints, 10, new Color(0.1F, 0.3F, 1, 0.6F), 30, 0.3),
			Iters.from(controlPoints)
			     .map(p -> new Geometry.PointsLine(List.of(p, p.add(0, 2, new Vector2f())), 2, Color.RED))
			     .toList()
		
		));
		lineRenderer.submit(window, buf, frameID, lineRes);
	}
	private void renderAutoSizeByteGrid(int frameID, CommandBuffer buf) throws VulkanCodeException{
		int count = 32*32;
		var space = window.swapchain.extent;
		
		int bytesPerRow = 1;
		
		while(true){
			var byteSize   = space.width/(float)bytesPerRow;
			var rows       = Math.ceilDiv(count, bytesPerRow);
			var rowsHeight = rows*byteSize;
			if(rowsHeight>=space.height){
				bytesPerRow++;
			}else{
				break;
			}
		}
		var byteSize = space.width/(float)bytesPerRow;
		
		byteGridRender.record(
			grid1Res, frameID,
			new Matrix4f().translate(0, 0, 0)
			              .scale(byteSize),
			bytesPerRow
		);
		byteGridRender.submit(window, buf, frameID, grid1Res);
	}
	
	private static void testFontWave(List<MsdfFontRender.StringDraw> sd){
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
		var window = this.window.getGlfwWindow();
		var t = Thread.ofPlatform().name("render").start(() -> {
			boolean first = true;
			while(!window.shouldClose()){
				try{
					render();
					if(first){
						first = false;
						core.renderQueue.waitIdle();
						window.show();
					}
				}catch(Throwable e){
					e.printStackTrace();
					requestClose();
				}
			}
		});
		while(!window.shouldClose()){
			try{
				window.pollEvents();
			}catch(Throwable e){
				e.printStackTrace();
				requestClose();
			}
			try{
				Thread.sleep(5);
			}catch(InterruptedException e){ requestClose(); }
		}
		try{
			t.join();
		}catch(InterruptedException e){
			throw new RuntimeException(e);
		}
	}
	private void requestClose(){
		window.requestClose();
	}
	
	@Override
	public void close() throws VulkanCodeException{
		window.getGlfwWindow().hide();
		
		core.device.waitIdle();
		
		fontRender.destroy();
		
		grid1Res.destroy();
		byteGridRender.destroy();
		
		lineRes.destroy();
		lineRenderer.destroy();
		
		imGUIRenderer.destroy();
		
		graphicsBuffs.forEach(CommandBuffer::destroy);
		cmdPool.destroy();
		
		window.close();
		core.close();
	}
}
