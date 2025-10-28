package com.lapissea.dfs.inspect.display.imgui.components;

import com.lapissea.dfs.inspect.display.DeviceGC;
import com.lapissea.dfs.inspect.display.TextureRegistry;
import com.lapissea.dfs.inspect.display.imgui.UIComponent;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;

public class StatsComponent implements UIComponent{
	
	private final int[] level;
	public StatsComponent(int[] level){
		this.level = level;
	}
	
	@Override
	public void imRender(DeviceGC deviceGC, TextureRegistry.Scope tScope){
		switch(level[0]){
			case 1 -> renderFps();
			case 2 -> {
				var open = new ImBoolean(true);
				ImGui.showMetricsWindow(open);
				if(!open.get()){
					level[0] = 0;
				}
			}
		}
	}
	
	private boolean lastHovered;
	private void renderFps(){
		ImGui.setNextWindowPos(ImGui.getMainViewport().getPos());
		
		ImGui.pushStyleColor(ImGuiCol.WindowBg, 0, 0, 0, lastHovered? 30 : 60);
		int flags = ImGuiWindowFlags.NoDecoration|ImGuiWindowFlags.AlwaysAutoResize|
		            ImGuiWindowFlags.NoFocusOnAppearing|ImGuiWindowFlags.NoNav|ImGuiWindowFlags.NoDocking|ImGuiWindowFlags.NoInputs;
		ImGui.begin("FPS Overlay", new ImBoolean(true), flags);
		
		ImGui.textColored(lastHovered? 0x55FFFFFF : 0xFFFFFFFF, String.format("FPS: %.1f", ImGui.getIO().getFramerate()));
		ImVec2 pos = ImGui.getWindowPos();
		lastHovered = ImGui.isMouseHoveringRect(pos, pos.plus(ImGui.getWindowSize()));
		ImGui.end();
		ImGui.popStyleColor();
	}
	
	@Override
	public void unload(TextureRegistry.Scope tScope){ }
}
