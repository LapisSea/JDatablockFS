package com.lapissea.dfs.inspect.display.imgui;

import com.lapissea.dfs.inspect.display.DeviceGC;
import com.lapissea.dfs.inspect.display.TextureRegistry;
import com.lapissea.dfs.inspect.display.VulkanCodeException;

public interface UIComponent{
	
	void imRender(DeviceGC deviceGC, TextureRegistry.Scope tScope);
	
	void unload(TextureRegistry.Scope tScope) throws VulkanCodeException;
	
}
