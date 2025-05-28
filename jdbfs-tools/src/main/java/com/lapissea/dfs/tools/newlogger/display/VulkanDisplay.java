package com.lapissea.dfs.tools.newlogger.display;

import com.lapissea.dfs.tools.newlogger.display.imgui.ImHandler;
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

import java.awt.Color;
import java.io.File;
import java.time.Instant;
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
	
	private final CommandPool cmdPool;
	
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
	
	ImHandler imHandler;
	
	public VulkanDisplay(){
		try{
			ImGui.setCurrentContext(ImGui.createContext());
			core = new VulkanCore("DFS debugger", VKPresentMode.IMMEDIATE);
			
			window = createMainWindow();
			
			fontRender = new MsdfFontRender(core);
			byteGridRender = new ByteGridRender(core);
			lineRenderer = new LineRenderer(core);
			imGUIRenderer = new ImGUIRenderer(core);
			
			imHandler = new ImHandler(core, window, imGUIRenderer);
			
			cmdPool = core.device.createCommandPool(core.renderQueueFamily, CommandPool.Type.NORMAL);
			
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
		var window = new VulkanWindow(core, true);
		
		var win = window.getGlfwWindow();
		win.registryKeyboardKey.register(GLFW.GLFW_KEY_ESCAPE, GlfwKeyboardEvent.Type.DOWN, e -> window.requestClose());
		
		var winFile = new File("winRemember.json");
		win.loadState(winFile);
		win.autoHandleStateSaving(winFile);
		
		return window;
	}
	
	private void render(VulkanWindow window) throws VulkanCodeException{
		if(window.swapchain == null){
			UtilL.sleep(50);
			handleResize(window);
			return;
		}
		try{
			renderQueue(window);
		}catch(VulkanCodeException e){
			switch(e.code){
				case SUBOPTIMAL_KHR, ERROR_OUT_OF_DATE_KHR -> {
					handleResize(window);
				}
				default -> throw new RuntimeException("Failed to render frame", e);
			}
		}
	}
	
	private void renderQueue(VulkanWindow window) throws VulkanCodeException{
		window.renderQueue((win, frameID, buf, fb) -> {
			try(var ignore = buf.beginRenderPass(core.renderPass, fb, win.swapchain.extent.asRect(), clearColor)){
				recordToBuff(win, buf, frameID);
			}
		});
		
		var w = window.getGlfwWindow();
		if(!w.isVisible() && !w.shouldClose()){
			core.device.waitIdle();
			w.show();
		}
	}
	
	private double time;
	
	private void imguiUpdate(VulkanWindow window){
		var io    = ImGui.getIO();
		var gwin  = window.getGlfwWindow();
		var mouse = gwin.mousePos;
		
		var extent = window.swapchain.extent;
		io.setDisplaySize(extent.width, extent.height);
		
		final double currentTime = glfwGetTime();
		io.setDeltaTime(time>0.0? (float)(currentTime - time) : 1.0f/60.0f);
		time = currentTime;
		
		io.setMousePos(mouse.x(), mouse.y());
		
		io.setMouseDown(0, gwin.isMouseKeyDown(GLFW.GLFW_MOUSE_BUTTON_LEFT));
		io.setMouseDown(1, gwin.isMouseKeyDown(GLFW.GLFW_MOUSE_BUTTON_RIGHT));
		io.setMouseDown(2, gwin.isMouseKeyDown(GLFW.GLFW_MOUSE_BUTTON_MIDDLE));
		
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
	private void handleResize(VulkanWindow window) throws VulkanCodeException{
		resizing = true;
		try{
			window.recreateSwapchainContext();
			if(window.swapchain == null){
				return;
			}
			
			try{
				renderQueue(window);
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
	private void recordToBuff(VulkanWindow window, CommandBuffer buf, int frameID) throws VulkanCodeException{

//		renderAutoSizeByteGrid(window, frameID, buf);
//
//		List<MsdfFontRender.StringDraw> sd = new ArrayList<>();
//
//		//testFontWave(sd);
//
//		sd.add(new MsdfFontRender.StringDraw(
//			100, new Color(0.1F, 0.3F, 1, 1), "Hello world UwU", 100, 200));
//		sd.add(new MsdfFontRender.StringDraw(
//			100, new Color(1, 1, 1F, 0.5F), "Hello world UwU", 100, 200, 1, 1.5F));
//		fontRender.render(window, buf, frameID, sd);
//
//
//		renderDecimatedCurve(frameID, buf);
//
//		imguiUpdate(window);
//
//		ImGui.newFrame();
//		ImGui.dockSpaceOverViewport();
//
//		ImGui.dockSpace(1);
//		if(ImGui.begin("Main")){
//			ImGui.text("Hello from Java + Vulkan!");
//			ImGui.sliderInt("##Frame slider", selectedImageIndexBuf, 0, 255);
//			ImGui.text("Frame: " + selectedImageIndexBuf[0]);
//		}
//		ImGui.end();
//
//		if(ImGui.begin("Binary view")){
//			ImGui.textColored(0xFF0000FF, "TODO");
//		}
//		ImGui.end();
//
//		if(ImGui.begin("Reference tree view")){
//			ImGui.textColored(0xFF0000FF, "TODO");
//		}
//		ImGui.end();
//
//		ImGui.render();
		
		imGUIRenderer.submit(window, buf, frameID, ImGui.getMainViewport().getDrawData());
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
	private void renderAutoSizeByteGrid(VulkanWindow window, int frameID, CommandBuffer buf) throws VulkanCodeException{
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
	
	private void renderLoop(VulkanWindow window){
		boolean first   = true;
		var     glfwWin = window.getGlfwWindow();
		while(!glfwWin.shouldClose()){
			try{
				render(window);
				if(first){
					first = false;
					core.renderQueue.waitIdle();
					glfwWin.show();
				}
			}catch(Throwable e){
				e.printStackTrace();
				requestClose();
			}
		}
	}
	
	public void run(){
		var window = this.window.getGlfwWindow();
		var t = Thread.ofPlatform().name("render").start(() -> {
		
		});
		while(!window.shouldClose()){
			try{
				window.grabContext();
				window.pollEvents();
				
				imHandler.renderAll();
				
				core.device.waitIdle();
				render(this.window);
//
//				core.device.waitIdle();
//				render(this.window2);
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
		
		cmdPool.destroy();
		
		window.close();
		core.close();
	}
}
