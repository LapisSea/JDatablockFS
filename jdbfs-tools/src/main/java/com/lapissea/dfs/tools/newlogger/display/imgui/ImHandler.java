package com.lapissea.dfs.tools.newlogger.display.imgui;

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
	
	public ImHandler(VulkanCore core, VulkanWindow window, ImGUIRenderer imGuiRenderer){
		this.imGuiRenderer = imGuiRenderer;
		
		ImGui.setCurrentContext(ImGui.createContext());
		
		ImGuiIO io = ImGui.getIO();
		io.addConfigFlags(ImGuiConfigFlags.DockingEnable|ImGuiConfigFlags.ViewportsEnable|
		                  ImGuiConfigFlags.NavEnableKeyboard|ImGuiConfigFlags.NavEnableSetMousePos);
		io.setConfigDockingTransparentPayload(true);
		
		
		ImTools.setupFont("/CourierPrime/Regular/font.ttf");
		
		imGuiImpl = new ImGuiImpl(core, imGuiRenderer);
		imGuiImpl.init(window, true);
	}
	
	public void addComponent(UIComponent component){
		components.add(component);
	}
	
	public void doFrame(){
		imGuiRenderer.checkFonts();
		imGuiImpl.newFrame();
		ImGui.newFrame();
		renderImGUI();
		ImGui.render();
		ImGui.updatePlatformWindows();
	}
	
	
	private void renderImGUI(){
		ImGui.dockSpaceOverViewport(ImGui.getMainViewport());
		
		for(UIComponent component : components){
			component.imRender(imGuiRenderer.textureScope);
		}
//		ImGui.showDemoWindow();
//		ImGui.showMetricsWindow();
	}
	
	
	public void close() throws VulkanCodeException{
		for(UIComponent component : components){
			component.unload(imGuiRenderer.textureScope);
		}
		imGuiImpl.shutdown();
	}
	
}
