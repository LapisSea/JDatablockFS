package com.lapissea.dfs.tools.newlogger.display.imgui.components;

import com.lapissea.dfs.tools.newlogger.display.DeviceGC;
import com.lapissea.dfs.tools.newlogger.display.TextureRegistry;
import com.lapissea.dfs.tools.newlogger.display.imgui.UIComponent;
import imgui.ImGui;

import java.util.List;

public class MessagesComponent implements UIComponent{
	
	private final List<String> messages;
	public MessagesComponent(List<String> messages){ this.messages = messages; }
	
	@Override
	public void imRender(DeviceGC deviceGC, TextureRegistry.Scope tScope){
		ImGui.begin("Messages");
		for(String message : messages){
			ImGui.separator();
			ImGui.textWrapped(message);
		}
		ImGui.end();
	}
	
	@Override
	public void unload(TextureRegistry.Scope tScope){ }
}
