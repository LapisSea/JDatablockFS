package com.lapissea.dfs.tools.newlogger.display;

import com.lapissea.dfs.io.IOInterface;
import com.lapissea.dfs.io.impl.MemoryData;
import com.lapissea.dfs.tools.newlogger.SessionSetView;
import com.lapissea.dfs.tools.newlogger.display.imgui.ImHandler;
import com.lapissea.dfs.tools.newlogger.display.imgui.UIComponent;
import com.lapissea.dfs.tools.newlogger.display.imgui.components.ByteGridComponent;
import com.lapissea.dfs.tools.newlogger.display.imgui.components.ImageViewerComp;
import com.lapissea.dfs.tools.newlogger.display.imgui.components.MessagesComponent;
import com.lapissea.dfs.tools.newlogger.display.imgui.components.StatsComponent;
import com.lapissea.dfs.tools.newlogger.display.renderers.ImGUIRenderer;
import com.lapissea.dfs.tools.newlogger.display.renderers.LineRenderer;
import com.lapissea.dfs.tools.newlogger.display.renderers.MsdfFontRender;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanCore;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VKPresentMode;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkSampleCountFlag;
import com.lapissea.dfs.tools.utils.NanoClock;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.glfw.GlfwKeyboardEvent;
import com.lapissea.glfw.GlfwWindow;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;
import imgui.ImGui;
import imgui.type.ImBoolean;
import imgui.type.ImInt;
import imgui.type.ImString;
import org.joml.Vector4f;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.glfw.GLFW.glfwPollEvents;

public class VulkanDisplay implements AutoCloseable{
	
	static{
		Thread.ofVirtual().start(GlfwWindow::initGLFW);
	}
	
	private final class SettingsUI implements UIComponent{
		
		final ImInt     fpsLimit                = new ImInt(60);
		final int[]     statsLevel              = {0};
		final ImBoolean imageViewerOpen         = new ImBoolean();
		final ImBoolean byteGridOpen            = new ImBoolean(true);
		final ImInt     byteGridSampleEnumIndex = new ImInt(2);
		final ImString  currentSessionName      = new ImString();
		final int[]     currentSessionFrame     = new int[1];
		
		VkSampleCountFlag[] samplesSet;
		
		@Override
		public void imRender(DeviceGC deviceGC, TextureRegistry.Scope tScope){
			if(ImGui.begin("Settings")){
				
				ImGui.inputInt("FPS Limit", fpsLimit);
				if(fpsLimit.get()<0) fpsLimit.set(0);
				ImGui.sliderScalar("##FPS Limit", fpsLimit.getData(), 0, 300);
				ImGui.separator();
				
				ImGui.sliderScalar("View Stats", statsLevel, 0, 2);
				ImGui.separator();
				
				enableButton("Image viewer", imageViewerOpen);
				ImGui.sameLine();
				enableButton("Byte grid viewer", byteGridOpen);
				
				ImGui.separator();
				if(byteGridOpen.get()){
					if(ImGui.beginCombo("Sample count", sampleName(byteGridSampleEnumIndex.get()))){
						for(int i = 0; i<samplesSet.length; i++){
							var selected = byteGridSampleEnumIndex.get() == i;
							if(ImGui.selectable(sampleName(i), selected)){
								byteGridSampleEnumIndex.set(i);
							}
							if(selected) ImGui.setItemDefaultFocus();
						}
						ImGui.endCombo();
					}
					
					if(ImGui.beginCombo("Session", currentSessionName.get())){
						for(String name : sessionSetView.getSessionNames()){
							var selected = currentSessionName.get().equals(name);
							if(ImGui.selectable(name, selected)){
								currentSessionName.set(name);
								forceSessionUpdate = true;
							}
							if(selected) ImGui.setItemDefaultFocus();
						}
						ImGui.endCombo();
					}
					ImGui.separator();
					
					var sesName = uiSettings.currentSessionName.get();
					var sesV    = sessionSetView.getSession(sesName);
					if(sesV.isPresent()){
						var ses = sesV.get();
						if(ImGui.sliderScalar("##Session frame", currentSessionFrame, 1, ses.frameCount())){
							var data = ses.getFrameData(currentSessionFrame[0]);
							try{
								byteGridComponent.setDisplayData(data.asReadOnly());
							}catch(IOException e){
								new RuntimeException("Failed to update data", e).printStackTrace();
							}
						}
					}
				}
				ImGui.separator();
			}
			ImGui.end();
		}
		private void enableButton(String label, ImBoolean value){
			ImGui.beginDisabled(value.get());
			if(ImGui.button(label)){
				value.set(true);
			}
			ImGui.endDisabled();
		}
		
		private void setMaxSample(VkSampleCountFlag max){
			
			samplesSet = Iters.from(VkSampleCountFlag.class)
			                  .takeWhile(e -> e.ordinal()<=max.ordinal())
			                  .toArray(VkSampleCountFlag[]::new);
			
			byteGridSampleEnumIndex.set(Math.min(byteGridSampleEnumIndex.get(), samplesSet.length - 1));
		}
		
		private String sampleName(int cid){
			return samplesSet[cid].name().substring(1) + " " + TextUtil.plural("sample", cid + 1);
		}
		@Override
		public void unload(TextureRegistry.Scope tScope){ }
	}
	
	private final VulkanCore core;
	
	public final MsdfFontRender fontRender;
	public final LineRenderer   lineRenderer;
	public final ImGUIRenderer  imGUIRenderer;
	
	private final VulkanWindow window;
	
	private final ImHandler imHandler;
	
	private final ByteGridComponent byteGridComponent;
	
	private       boolean        forceSessionUpdate;
	private final SessionSetView sessionSetView;
	private final SettingsUI     uiSettings;
	
	private final List<String> uiMessages = new ArrayList<>();
	
	public VulkanDisplay(SessionSetView sessionSetView){
		this.sessionSetView = sessionSetView;
		try{
			core = new VulkanCore("DFS debugger", VKPresentMode.IMMEDIATE);
			
			window = createMainWindow();
			
			fontRender = new MsdfFontRender(core);
			lineRenderer = new LineRenderer(core);
			imGUIRenderer = new ImGUIRenderer(core);
			
			imHandler = new ImHandler(core, window, imGUIRenderer);
			
			uiSettings = new SettingsUI();
			uiSettings.setMaxSample(core.physicalDevice.samples);
			
			imHandler.addComponent(new StatsComponent(uiSettings.statsLevel));
			imHandler.addComponent(uiSettings);
			imHandler.addComponent(new ImageViewerComp(uiSettings.imageViewerOpen));
			imHandler.addComponent(
				byteGridComponent = new ByteGridComponent(core, uiSettings.byteGridOpen, uiSettings.byteGridSampleEnumIndex, uiMessages,
				                                          lineRenderer, fontRender)
			);
			imHandler.addComponent(new MessagesComponent(uiMessages));
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
		
		var second   = Duration.ofSeconds(1);
		var lastTime = NanoClock.now();
		while(!window.shouldClose()){
			glfwPollEvents();
			int fpsLimit = uiSettings.fpsLimit.get();
			if(fpsLimit>0){
				Instant now, releaseTime = lastTime.plus(second.dividedBy(fpsLimit));
				while((now = NanoClock.now()).isBefore(releaseTime)){
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
			updateData();
		}
		uiMessages.clear();
		try{
			imHandler.newFrame(window.frameGC);
			render(this.window);
			ImGui.renderPlatformWindowsDefault();
			core.executeSwaps();
		}catch(VulkanCodeException e){
			window.requestClose();
			throw new RuntimeException("Failed to render", e);
		}
	}
	
	private void updateData(){
		var sesName = uiSettings.currentSessionName.get();
		var ses     = sessionSetView.getSession(sesName);
		if(ses.isEmpty()){
			var defSes = sessionSetView.getAnySession();
			if(defSes.isPresent()){
				ses = defSes;
				uiSettings.currentSessionName.set(ses.get().name());
			}
		}
		
		IOInterface data;
		if(ses.isEmpty()){
			data = MemoryData.empty();
		}else{
			var session = ses.get();
			data = session.getFrameData(session.frameCount() - 1);
			uiSettings.currentSessionFrame[0] = session.frameCount() - 1;
		}
		try{
			byteGridComponent.setDisplayData(data.asReadOnly());
		}catch(IOException e){
			new RuntimeException("Failed to update data", e).printStackTrace();
		}
	}
	
	@Override
	public void close() throws VulkanCodeException{
		window.getGlfwWindow().hide();
		
		core.device.waitIdle();
		
		imHandler.close(window.frameGC);
		
		fontRender.destroy();
		lineRenderer.destroy();
		imGUIRenderer.destroy();
		
		window.close();
		core.close();
	}
}
