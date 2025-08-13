package com.lapissea.dfs.tools.newlogger.display.imgui;

import com.lapissea.dfs.tools.newlogger.display.DeviceGC;
import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.VulkanWindow;
import com.lapissea.dfs.tools.newlogger.display.renderers.ImGUIRenderer;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanCore;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.flag.ImGuiConfigFlags;

import java.util.ArrayList;
import java.util.List;

public class ImHandler{
	
	private final ImGuiImpl     imGuiImpl;
	private final ImGUIRenderer imGuiRenderer;
	
	private final List<UIComponent> components = new ArrayList<>();
	
	@SuppressWarnings("MismatchedQueryAndUpdateOfCollection") //Delay GC of buffer as ImGUI is using it
	private final List<byte[]> rawFontResources = new ArrayList<>();
	
	public ImHandler(VulkanCore core, VulkanWindow window, ImGUIRenderer imGuiRenderer){
		this.imGuiRenderer = imGuiRenderer;
		
		ImGui.setCurrentContext(ImGui.createContext());
		
		ImGuiIO io = ImGui.getIO();
		io.addConfigFlags(ImGuiConfigFlags.DockingEnable|ImGuiConfigFlags.NavEnableKeyboard|ImGuiConfigFlags.DpiEnableScaleFonts);
		if(ImGuiImpl.supportsViewports()){
			io.addConfigFlags(ImGuiConfigFlags.ViewportsEnable|ImGuiConfigFlags.DpiEnableScaleViewports);
		}
		io.setConfigDockingTransparentPayload(true);
		io.setConfigWindowsResizeFromEdges(true);
		
		
		rawFontResources.addAll(ImTools.setupFont(io, "/CourierPrime/Regular/font.ttf"));
		
		imGuiImpl = new ImGuiImpl(core, imGuiRenderer);
		imGuiImpl.init(window, true);
		
		imGuiRenderer.checkFonts();
	}
	
	public void addComponent(UIComponent component){
		components.add(component);
	}
	
	public void newFrame(DeviceGC deviceGC){
		imGuiRenderer.checkFonts();
		rawFontResources.clear();
		imGuiImpl.newFrame();
		ImGui.newFrame();
		renderImGUI(deviceGC);
		ImGui.render();
		ImGui.updatePlatformWindows();
	}
	
	
	private void renderImGUI(DeviceGC deviceGC){
		ImGui.dockSpaceOverViewport(ImGui.getMainViewport());
		
		for(UIComponent component : components){
			component.imRender(deviceGC, imGuiRenderer.textureScope);
		}
//		ImGui.showDemoWindow();
//		ImGui.showMetricsWindow();
	}
	
	
	public void close(DeviceGC deviceGC) throws VulkanCodeException{
		for(UIComponent component : components){
			component.unload(imGuiRenderer.textureScope);
		}
		imGuiImpl.shutdown();
	}
	
}
