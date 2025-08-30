package com.lapissea.dfs.tools.newlogger.display.imgui.components;

import com.lapissea.dfs.tools.newlogger.display.DeviceGC;
import com.lapissea.dfs.tools.newlogger.display.TextureRegistry;
import com.lapissea.dfs.tools.newlogger.display.imgui.UIComponent;
import imgui.ImGui;
import imgui.type.ImBoolean;

import java.util.List;
import java.util.Objects;

public class MessagesComponent implements UIComponent{
	
	private final List<String> messages;
	private final String       title;
	private final ImBoolean    open;
	
	public MessagesComponent(List<String> messages, String title, ImBoolean open){
		this.messages = messages;
		this.title = Objects.requireNonNull(title);
		this.open = Objects.requireNonNull(open);
	}
	
	@Override
	public void imRender(DeviceGC deviceGC, TextureRegistry.Scope tScope){
		if(!open.get()) return;
		
		ImGui.begin(title, open);
		for(String message : messages){
			ImGui.separator();
			ImGui.textWrapped(message);
		}
		ImGui.end();
	}
	
	@Override
	public void unload(TextureRegistry.Scope tScope){ }
}
