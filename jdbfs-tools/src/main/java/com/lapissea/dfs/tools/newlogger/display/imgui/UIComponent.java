package com.lapissea.dfs.tools.newlogger.display.imgui;

import com.lapissea.dfs.tools.newlogger.display.TextureRegistry;
import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;

public interface UIComponent{
	
	void imRender(TextureRegistry.Scope tScope);
	
	void unload(TextureRegistry.Scope tScope) throws VulkanCodeException;
	
}
