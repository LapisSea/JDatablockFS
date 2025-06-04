package com.lapissea.dfs.tools.newlogger.display.imgui;

import com.lapissea.dfs.tools.newlogger.display.TextureRegistry;

public interface UIComponent{
	
	void unload(TextureRegistry.Scope tScope);
	
	void imRender(TextureRegistry.Scope tScope);
	
}
