package com.lapissea.dfs.tools.newlogger.display.imgui;

import com.lapissea.dfs.tools.newlogger.display.DeviceGC;
import com.lapissea.dfs.tools.newlogger.display.TextureRegistry;
import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;

public interface UIComponent{
	
	void imRender(DeviceGC deviceGC, TextureRegistry.Scope tScope);
	
	void unload(DeviceGC deviceGC, TextureRegistry.Scope tScope) throws VulkanCodeException;
	
}
