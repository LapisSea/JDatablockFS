package com.lapissea.dfs.tools.newlogger.display;

import com.lapissea.dfs.tools.newlogger.display.imgui.ImHandler;
import com.lapissea.dfs.tools.newlogger.display.renderers.ByteGridRender;
import com.lapissea.dfs.tools.newlogger.display.renderers.Geometry;
import com.lapissea.dfs.tools.newlogger.display.renderers.ImGUIRenderer;
import com.lapissea.dfs.tools.newlogger.display.renderers.LineRenderer;
import com.lapissea.dfs.tools.newlogger.display.renderers.MsdfFontRender;
import com.lapissea.dfs.tools.newlogger.display.vk.CommandBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanCore;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VKPresentMode;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.CommandPool;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Extent2D;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VulkanQueue;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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
			
			byte[] bytes = new RawRandom(10).nextBytes(32*32);
			
			byteGridRender.record(
				grid1Res,
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
			
			lineRenderer.record(lineRes, List.of(
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
		var window = new VulkanWindow(core, true, false);
		
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
		var swap = renderQueue(window);
		core.pushSwap(swap);
	}
	
	private VulkanQueue.SwapSync.PresentFrame renderQueue(VulkanWindow window) throws VulkanCodeException{
		var present = window.renderQueueNoSwap((win, frameID, buf, fb) -> {
			try(var ignore = buf.beginRenderPass(core.renderPass, fb, win.swapchain.extent.asRect(), clearColor)){
				recordToBuff(win, buf, frameID);
			}
		});
		
		var w = window.getGlfwWindow();
		if(!w.isVisible() && !w.shouldClose()){
			core.device.waitIdle();
			w.show();
		}
		return present;
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
			core.device.waitIdle();
			try{
				var swap = renderQueue(window);
				core.renderQueue.present(List.of(swap));
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
	
	private void recordToBuff(VulkanWindow window, CommandBuffer buf, int frameID) throws VulkanCodeException{
		
		renderAutoSizeByteGrid(window, frameID, buf);
		
		List<MsdfFontRender.StringDraw> sd = new ArrayList<>();
		
		testFontWave(sd);
		
		sd.add(new MsdfFontRender.StringDraw(
			100, new Color(0.1F, 0.3F, 1, 1), "Hello world UwU", 100, 200));
		sd.add(new MsdfFontRender.StringDraw(
			100, new Color(1, 1, 1F, 0.5F), "Hello world UwU", 100, 200, 1, 1.5F));
		fontRender.render(window, buf, frameID, sd);
		
		
		renderDecimatedCurve(window, buf);
		
		imGUIRenderer.submit(buf, frameID, window.imguiResource.get(frameID), ImGui.getMainViewport().getDrawData());
	}
	
	private void renderDecimatedCurve(VulkanWindow window, CommandBuffer buf) throws VulkanCodeException{
		var t = (System.currentTimeMillis())/500D;
		
		var controlPoints = Iters.of(3D, 2D, 1D, 4D, 5D).enumerate((i, s) -> new Vector2f(
			(float)Math.sin(t/s)*100 + 200*(i + 1),
			(float)Math.cos(t/s)*100 + 200
		)).toList();
		
		lineRenderer.record(lineRes, Iters.concat1N(
			new Geometry.BezierCurve(controlPoints, 10, new Color(0.1F, 0.3F, 1, 0.6F), 30, 0.3),
			Iters.from(controlPoints)
			     .map(p -> new Geometry.PointsLine(List.of(p, p.add(0, 2, new Vector2f())), 2, Color.RED))
			     .toList()
		
		));
		
		lineRenderer.submit(this.window, buf, window.projectionMatrix2D, lineRes);
	}
	
	private void renderAutoSizeByteGrid(VulkanWindow window, int frameID, CommandBuffer buf) throws VulkanCodeException{
		int byteCount  = 32*32;
		var windowSize = window.swapchain.extent;
		
		var res = ByteGridSize.compute(windowSize, byteCount);
		byteGridRender.submit(window, buf, frameID, new Matrix4f().scale(res.byteSize), res.bytesPerRow, grid1Res);
	}
	
	private record ByteGridSize(int bytesPerRow, float byteSize){
		private static ByteGridSize compute(Extent2D windowSize, int byteCount){
			float aspectRatio = windowSize.width/(float)windowSize.height;
			int   bytesPerRow = (int)Math.ceil(Math.sqrt(byteCount*aspectRatio));
			
			float byteSize = windowSize.width/(float)bytesPerRow;
			while(true){
				int   rows        = Math.ceilDiv(byteCount, bytesPerRow);
				float totalHeight = rows*byteSize;
				
				if(totalHeight<=windowSize.height){
					break;
				}
				bytesPerRow++;
				byteSize = windowSize.width/(float)bytesPerRow;
			}
			return new ByteGridSize(bytesPerRow, byteSize);
		}
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
	
	public void run(){
		var window = this.window.getGlfwWindow();
		window.size.register(this::onResizeEvent);
		
		var imHandler = new ImHandler(core, this.window, imGUIRenderer);
//		Thread.ofPlatform().start(() -> {
		try{
			var lastTime = Instant.now();
			while(!window.shouldClose()){
				while(Duration.between(lastTime, Instant.now()).compareTo(Duration.ofMillis(6))<0) UtilL.sleep(1);
				lastTime = Instant.now();
				
				window.grabContext();
				window.pollEvents();
				imHandler.doFrame();
				
				render(this.window);
				imHandler.renderViewports();
				
				core.executeSwaps();
			}
		}catch(Throwable e){
			e.printStackTrace();
			requestClose();
		}finally{
			imHandler.close();
		}
//		});
//
//		window.whileOpen(() -> {
//			try{
//				Thread.sleep(5);
//			}catch(InterruptedException e){ requestClose(); }
//
//			window.grabContext();
//			window.pollEvents();
//  //		imHandler.poolWindowEvents();
//		});
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
