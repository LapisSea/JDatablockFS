package com.lapissea.dfs.tools.newlogger.display.imgui;

import com.lapissea.dfs.tools.newlogger.display.VulkanWindow;
import com.lapissea.dfs.tools.newlogger.display.renderers.ImGUIRenderer;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanCore;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.flag.ImGuiConfigFlags;

public class ImHandler{
	
	private final ImGuiImplGlfw imGuiImplGlfw;
	
	public ImHandler(VulkanCore core, VulkanWindow window, ImGUIRenderer imGuiRenderer){
		ImGui.setCurrentContext(ImGui.createContext());
		
		ImGuiIO io = ImGui.getIO();
		io.addConfigFlags(ImGuiConfigFlags.DockingEnable|ImGuiConfigFlags.ViewportsEnable);
		io.setConfigDockingTransparentPayload(true);
		
		ImTools.setupFont("/CourierPrime/Regular/font.ttf");
		
		imGuiImplGlfw = new ImGuiImplGlfw(core, imGuiRenderer);
		imGuiImplGlfw.init(window, true);
	}
	
	public void poolWindowEvents(){
		imGuiImplGlfw.poolWindowEvents();
	}
	
	public void doFrame(){
		imGuiImplGlfw.newFrame();
		ImGui.newFrame();
		ImGui.dockSpaceOverViewport(ImGui.getMainViewport());
		ImGui.showDemoWindow();
		ImGui.showMetricsWindow();
		ImGui.render();
	}
	
	public void renderViewports(){
		ImGui.updatePlatformWindows();
		ImGui.renderPlatformWindowsDefault();
	}
	
	public void close(){
		imGuiImplGlfw.shutdown();
	}
	
}
