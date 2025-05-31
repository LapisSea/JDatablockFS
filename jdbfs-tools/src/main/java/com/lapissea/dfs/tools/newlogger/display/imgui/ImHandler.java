package com.lapissea.dfs.tools.newlogger.display.imgui;

import com.lapissea.dfs.tools.newlogger.display.VulkanWindow;
import com.lapissea.dfs.tools.newlogger.display.renderers.ImGUIRenderer;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanCore;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.flag.ImGuiConfigFlags;

public class ImHandler{
	
	private final ImGuiImpl imGuiImpl;
	
	public ImHandler(VulkanCore core, VulkanWindow window, ImGUIRenderer imGuiRenderer){
		ImGui.setCurrentContext(ImGui.createContext());
		
		ImGuiIO io = ImGui.getIO();
		io.addConfigFlags(ImGuiConfigFlags.DockingEnable|ImGuiConfigFlags.ViewportsEnable|
		                  ImGuiConfigFlags.NavEnableKeyboard|ImGuiConfigFlags.NavEnableSetMousePos);
		io.setConfigDockingTransparentPayload(true);
		
		
		ImTools.setupFont("/CourierPrime/Regular/font.ttf");
		
		imGuiImpl = new ImGuiImpl(core, imGuiRenderer);
		imGuiImpl.init(window, true);
	}
	
	public void poolWindowEvents(){
		imGuiImpl.poolWindowEvents();
	}
	
	public void doFrame(){
		imGuiImpl.newFrame();
		ImGui.newFrame();
		renderImGUI();
		ImGui.render();
	}
	
	private static void renderImGUI(){
		ImGui.dockSpaceOverViewport(ImGui.getMainViewport());
		ImGui.showDemoWindow();
		ImGui.showMetricsWindow();
	}
	
	public void renderViewports(){
		ImGui.updatePlatformWindows();
		ImGui.renderPlatformWindowsDefault();
	}
	
	public void close(){
		imGuiImpl.shutdown();
	}
	
}
