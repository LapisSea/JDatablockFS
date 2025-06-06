package com.lapissea.dfs.tools.newlogger.display.imgui.components;

import com.lapissea.dfs.tools.newlogger.display.TextureRegistry;
import com.lapissea.dfs.tools.newlogger.display.imgui.UIComponent;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;

public class FPSComponent implements UIComponent{
	
	@Override
	public void imRender(TextureRegistry.Scope tScope){
		ImGui.setNextWindowPos(ImGui.getMainViewport().getPos());
		
		int flags = ImGuiWindowFlags.NoDecoration|ImGuiWindowFlags.AlwaysAutoResize|
		            ImGuiWindowFlags.NoFocusOnAppearing|ImGuiWindowFlags.NoNav|ImGuiWindowFlags.NoDocking|ImGuiWindowFlags.NoInputs;
		ImGui.begin("FPS Overlay", new ImBoolean(true), flags|ImGuiWindowFlags.NoBackground);
		
		ImVec2 pos     = ImGui.getWindowPos();
		var    hovered = ImGui.isMouseHoveringRect(pos, pos.plus(ImGui.getWindowSize()));
		ImGui.textColored(hovered? 0x55FFFFFF : 0xFFFFFFFF, String.format("FPS: %.1f", ImGui.getIO().getFramerate()));
		ImGui.end();
	}
	
	@Override
	public void unload(TextureRegistry.Scope tScope){ }
}
