package com.lapissea.dfs.tools.newlogger.display.imgui;

import com.lapissea.dfs.tools.newlogger.display.VulkanWindow;
import com.lapissea.dfs.tools.newlogger.display.renderers.ImGUIRenderer;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanCore;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.ImVec2;
import imgui.flag.ImGuiConfigFlags;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;

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
	
	
	private void renderImGUI(){
		ImGui.dockSpaceOverViewport(ImGui.getMainViewport());
		
		showFps();
		
		ImGui.showDemoWindow();
		ImGui.showMetricsWindow();
	}
	
	private static void showFps(){
		ImGui.setNextWindowPos(ImGui.getMainViewport().getPos());
		
		int flags = ImGuiWindowFlags.NoDecoration|ImGuiWindowFlags.AlwaysAutoResize|
		            ImGuiWindowFlags.NoFocusOnAppearing|ImGuiWindowFlags.NoNav|ImGuiWindowFlags.NoDocking|ImGuiWindowFlags.NoInputs;
		ImGui.begin("FPS Overlay", new ImBoolean(true), flags|ImGuiWindowFlags.NoBackground);
		
		ImVec2 pos     = ImGui.getWindowPos();
		var    hovered = ImGui.isMouseHoveringRect(pos, pos.plus(ImGui.getWindowSize()));
		ImGui.textColored(hovered? 0x55FFFFFF : 0xFFFFFFFF, String.format("FPS: %.1f", ImGui.getIO().getFramerate()));
		ImGui.end();
	}
	
	public void renderViewports(){
		ImGui.updatePlatformWindows();
		ImGui.renderPlatformWindowsDefault();
	}
	
	public void close(){
		imGuiImpl.shutdown();
	}
	
}
