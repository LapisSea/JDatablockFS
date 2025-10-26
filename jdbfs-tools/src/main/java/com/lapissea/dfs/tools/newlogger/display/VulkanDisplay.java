package com.lapissea.dfs.tools.newlogger.display;

import com.lapissea.dfs.tools.newlogger.SessionSetView;
import com.lapissea.dfs.tools.newlogger.display.imgui.ImHandler;
import com.lapissea.dfs.tools.newlogger.display.imgui.components.ByteGridComponent;
import com.lapissea.dfs.tools.newlogger.display.imgui.components.ImageViewerComp;
import com.lapissea.dfs.tools.newlogger.display.imgui.components.MessagesComponent;
import com.lapissea.dfs.tools.newlogger.display.imgui.components.SettingsUIComponent;
import com.lapissea.dfs.tools.newlogger.display.imgui.components.StatsComponent;
import com.lapissea.dfs.tools.newlogger.display.renderers.ImGUIRenderer;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanCore;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VKPresentMode;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkSampleCountFlag;
import com.lapissea.dfs.tools.utils.NanoClock;
import com.lapissea.glfw.GlfwKeyboardEvent;
import com.lapissea.glfw.GlfwWindow;
import com.lapissea.util.UtilL;
import imgui.ImGui;
import org.joml.Vector4f;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.lwjgl.glfw.GLFW.glfwPollEvents;

public class VulkanDisplay implements AutoCloseable{
	
	static{
		Thread.ofVirtual().start(GlfwWindow::initGLFW);
	}
	
	private final VulkanCore core;
	
	public final ImGUIRenderer imGUIRenderer;
	
	private final VulkanWindow window;
	
	private final ImHandler imHandler;
	
	private final ByteGridComponent byteGridComponent;
	
	public boolean forceSessionUpdate;
	
	public final  SessionSetView      sessionSetView;
	private final SettingsUIComponent uiSettings;
	
	private final List<String> uiMessages = new ArrayList<>();
	
	public VulkanDisplay(SessionSetView sessionSetView){
		this.sessionSetView = sessionSetView;
		try{
			core = new VulkanCore("DFS debugger", VKPresentMode.IMMEDIATE);
			
			window = createMainWindow();
			
			imGUIRenderer = new ImGUIRenderer(core);
			
			imHandler = new ImHandler(core, window, imGUIRenderer);
			
			uiSettings = new SettingsUIComponent(this);
			uiSettings.setMaxSample(core.physicalDevice.samples);
			
			imHandler.addComponent(new StatsComponent(uiSettings.statsLevel));
			imHandler.addComponent(uiSettings);
			imHandler.addComponent(new ImageViewerComp(uiSettings.imageViewerOpen));
			imHandler.addComponent(
				byteGridComponent = new ByteGridComponent(core, uiSettings.byteGridOpen, uiSettings, uiMessages)
			);
			imHandler.addComponent(new MessagesComponent(uiMessages, "Messages", uiSettings.messagesOpen));
		}catch(VulkanCodeException e){
			throw new RuntimeException("Failed to init vulkan display", e);
		}
	}
	
	private VulkanWindow createMainWindow() throws VulkanCodeException{
		var window = new VulkanWindow(core, true, false, VkSampleCountFlag.N1);
		
		var win = window.getGlfwWindow();
		win.registryKeyboardKey.register(GLFW.GLFW_KEY_ESCAPE, GlfwKeyboardEvent.Type.DOWN, e -> window.requestClose());
		
		var winFile = new File("winRemember.json");
		win.loadState(winFile);
		win.autoHandleStateSaving(winFile);
		if(win.size.equals(0, 0)) win.size.set(100, 100);
		
		win.focus();
		return window;
	}
	
	private void render(VulkanWindow window) throws VulkanCodeException{
		if(window.swapchain == null){
			window.recreateSwapchainContext();
			return;
		}
		
		core.pushSwap(window.renderQueueNoSwap((win, frameID, buf, fb) -> {
			var renderPass = win.getSurfaceRenderPass();
			try(var ignore = buf.beginRenderPass(renderPass, fb, win.swapchain.extent.asRect(), new Vector4f(0, 0, 0, 1))){
				imGUIRenderer.submit(win.frameGC, buf, win.imguiResource.get(frameID), ImGui.getMainViewport().getDrawData());
			}
		}));
	}
	
	public void run() throws VulkanCodeException{
		var window = this.window.getGlfwWindow();
		window.show();
		
		var event = GLFW.glfwSetFramebufferSizeCallback(window.getHandle(), (window1, width, height) -> {
			try{
				this.window.recreateSwapchainContext();
			}catch(VulkanCodeException e){
				window.requestClose();
				throw new RuntimeException("Failed to render", e);
			}
			renderAndSwap();
		});
		window.pos.register(this::renderAndSwap);
		
		var mark = new AtomicBoolean();
		window.mousePos.register(() -> mark.set(true));
		
		var second   = Duration.ofSeconds(1);
		var lastTime = NanoClock.now();
		while(!window.shouldClose()){
			glfwPollEvents();
			int fpsLimit = uiSettings.fpsLimit.get();
			if(fpsLimit>0){
				Instant now, releaseTime = lastTime.plus(second.dividedBy(fpsLimit));
				while((now = NanoClock.now()).isBefore(releaseTime)){
					if(mark.get()){
						mark.set(false);
						break;
					}
					var remaning = Duration.between(now, releaseTime).toMillis();
					if(remaning>2){
						glfwPollEvents();
						UtilL.sleep(remaning/2);
					}else{
						Thread.yield();
					}
				}
				lastTime = now;
			}
			
			this.window.checkSwapchainSize();
			renderAndSwap();
		}
		
		GLFW.glfwSetFramebufferSizeCallback(window.getHandle(), event);
	}
	
	private void renderAndSwap(){
		if(window.swapchain == null || window.getGlfwWindow().size.equals(0, 0)){
			return;
		}
		
		if(forceSessionUpdate || sessionSetView.isDirty()){
			forceSessionUpdate = false;
			sessionSetView.clearDirty();
			if(forceSessionUpdate || uiSettings.lastFrame){
				setLastFrameData();
			}
		}
		uiMessages.clear();
		try{
			imHandler.newFrame(window.frameGC);
			render(this.window);
			ImGui.renderPlatformWindowsDefault();
			core.executeSwaps();
		}catch(Throwable e){
			window.requestClose();
			new RuntimeException("Failed to render... closing", e).printStackTrace();
		}
	}
	
	private void setLastFrameData(){
		var ses = getSessionView();
		setFrameData(ses, ses.map(e -> e.frameCount() - 1).orElse(-1));
	}
	public void setFrameData(Optional<SessionSetView.SessionView> sessionView, int frame){
		if(sessionView.isEmpty()){
			setFrameData0(SessionSetView.FrameData.EMPTY);
			return;
		}
		
		var session = sessionView.get();
		var data    = session.getFrameData(frame);
		uiSettings.currentSessionRange.setCurrentFrame(frame);
		setFrameData0(data);
	}
	
	public Optional<SessionSetView.SessionView> getSessionView(){
		var sesName = uiSettings.currentSessionName.get();
		var ses     = sessionSetView.getSession(sesName);
		if(ses.isEmpty()){
			var defSes = sessionSetView.getAnySession();
			if(defSes.isPresent()){
				ses = defSes;
				uiSettings.currentSessionName.set(ses.get().name());
			}
		}
		return ses;
	}
	
	private void setFrameData0(SessionSetView.FrameData data){
		try{
			byteGridComponent.setDisplayData(data);
		}catch(IOException e){
			new RuntimeException("Failed to update data", e).printStackTrace();
		}
		uiSettings.frameStacktrace = data.stacktrace();
	}
	
	@Override
	public void close() throws VulkanCodeException{
		window.getGlfwWindow().hide();
		
		core.device.waitIdle();
		
		imHandler.close(window.frameGC);
		
		imGUIRenderer.destroy();
		
		window.close();
		core.close();
	}
}
