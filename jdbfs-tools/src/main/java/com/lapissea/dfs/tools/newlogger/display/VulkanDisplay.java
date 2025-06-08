package com.lapissea.dfs.tools.newlogger.display;

import com.lapissea.dfs.tools.newlogger.display.imgui.ImHandler;
import com.lapissea.dfs.tools.newlogger.display.imgui.UIComponent;
import com.lapissea.dfs.tools.newlogger.display.imgui.components.ByteGridComponent;
import com.lapissea.dfs.tools.newlogger.display.imgui.components.ImageViewerComp;
import com.lapissea.dfs.tools.newlogger.display.renderers.ByteGridRender;
import com.lapissea.dfs.tools.newlogger.display.renderers.ImGUIRenderer;
import com.lapissea.dfs.tools.newlogger.display.renderers.LineRenderer;
import com.lapissea.dfs.tools.newlogger.display.renderers.MsdfFontRender;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanCore;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VKPresentMode;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VulkanQueue;
import com.lapissea.glfw.GlfwKeyboardEvent;
import com.lapissea.glfw.GlfwWindow;
import com.lapissea.util.UtilL;
import imgui.ImGui;
import imgui.type.ImBoolean;
import org.joml.Vector4f;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.time.Duration;
import java.time.Instant;

import static org.lwjgl.glfw.GLFW.glfwPollEvents;

public class VulkanDisplay implements AutoCloseable{
	
	static{
		Thread.ofVirtual().start(ImGui::init);
		Thread.ofVirtual().start(GlfwWindow::initGLFW);
	}
	
	private static final class SettingsUI implements UIComponent{
		
		final ImBoolean imageViewerOpen = new ImBoolean();
		final ImBoolean byteGridOpen    = new ImBoolean(true);
		
		@Override
		public void imRender(TextureRegistry.Scope tScope){
			if(ImGui.begin("Settings")){
				ImGui.checkbox("Image viewer", imageViewerOpen);
				ImGui.checkbox("Byte grid viewer", byteGridOpen);
			}
			ImGui.end();
		}
		@Override
		public void unload(TextureRegistry.Scope tScope){ }
	}
	
	private final VulkanCore core;
	
	public final MsdfFontRender fontRender;
	public final ByteGridRender byteGridRender;
	public final LineRenderer   lineRenderer;
	public final ImGUIRenderer  imGUIRenderer;
	
	private final VulkanWindow window;
	
	private final ImHandler imHandler;
	
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
			
			var settings = new SettingsUI();
			imHandler.addComponent(settings);
			imHandler.addComponent(new ImageViewerComp(settings.imageViewerOpen));
			imHandler.addComponent(new ByteGridComponent(settings.byteGridOpen, core, fontRender, byteGridRender, lineRenderer));
			
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
		if(win.size.equals(0, 0)) win.size.set(100, 100);
		
		return window;
	}
	
	private void render(VulkanWindow window) throws VulkanCodeException{
		if(window.swapchain == null){
			UtilL.sleep(50);
			window.recreateSwapchainContext();
			return;
		}
		var swap = renderQueue(window);
		core.pushSwap(swap);
	}
	
	private VulkanQueue.SwapSync.PresentFrame renderQueue(VulkanWindow window) throws VulkanCodeException{
		var present = window.renderQueueNoSwap((win, frameID, buf, fb) -> {
			try(var ignore = buf.beginRenderPass(core.renderPass, fb, win.swapchain.extent.asRect(), new Vector4f(0, 0, 0, 1))){
				imGUIRenderer.submit(buf, frameID, win.imguiResource.get(frameID), ImGui.getMainViewport().getDrawData());
			}
		});
		
		var w = window.getGlfwWindow();
		if(!w.isVisible() && !w.shouldClose()){
			core.device.waitIdle();
			w.show();
		}
		return present;
	}
	
	public void run() throws VulkanCodeException{
		var window = this.window.getGlfwWindow();
		
		GLFW.glfwSetFramebufferSizeCallback(window.getHandle(), (window1, width, height) -> {
			try{
				this.window.recreateSwapchainContext();
			}catch(VulkanCodeException e){
				window.requestClose();
				throw new RuntimeException("Failed to render", e);
			}
			renderAndSwap();
		});
		
		var lastTime = Instant.now();
		while(!window.shouldClose()){
			glfwPollEvents();
			while(Duration.between(lastTime, Instant.now()).compareTo(Duration.ofMillis(6))<0){
				glfwPollEvents();
				UtilL.sleep(1);
			}
			lastTime = Instant.now();
			renderAndSwap();
		}
	}
	
	private void renderAndSwap(){
		if(window.swapchain == null || window.getGlfwWindow().size.equals(0, 0)){
			return;
		}
		try{
			imHandler.newFrame();
			render(this.window);
			ImGui.renderPlatformWindowsDefault();
			core.executeSwaps();
		}catch(VulkanCodeException e){
			window.requestClose();
			throw new RuntimeException("Failed to render", e);
		}
	}
	
	@Override
	public void close() throws VulkanCodeException{
		window.getGlfwWindow().hide();
		
		core.device.waitIdle();
		
		imHandler.close();
		
		fontRender.destroy();
		byteGridRender.destroy();
		lineRenderer.destroy();
		imGUIRenderer.destroy();
		
		window.close();
		core.close();
	}
}
