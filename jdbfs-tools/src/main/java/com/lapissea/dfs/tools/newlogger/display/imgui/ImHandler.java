package com.lapissea.dfs.tools.newlogger.display.imgui;

import com.lapissea.dfs.tools.newlogger.display.VulkanWindow;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanCore;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.UtilL;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.flag.ImGuiConfigFlags;
import imgui.flag.ImGuiViewportFlags;

import java.util.HashMap;
import java.util.Map;

public class ImHandler{
	
	private final Map<Long, VulkanWindow> viewportMap = new HashMap<>();
	private final VulkanCore              core;
	
	public ImHandler(VulkanCore core){
		this.core = core;
		
		ImGui.setCurrentContext(ImGui.createContext());
		
		ImGuiIO io = ImGui.getIO();
		io.addConfigFlags(ImGuiConfigFlags.DockingEnable|ImGuiConfigFlags.ViewportsEnable);
		
		ImTools.setupFont("/CourierPrime/Regular/font.ttf");
	}
	
	public void renderAll(){
		ImGui.updatePlatformWindows();
		ImGui.renderPlatformWindowsDefault();
		
		
		var pio = ImGui.getPlatformIO();
		for(var viewport : Iters.range(0, pio.getViewportsSize()).mapToObj(pio::getViewports)){
			long handle = viewport.getPlatformHandle();
			
			if(UtilL.checkFlag(viewport.getFlags(), ImGuiViewportFlags.IsMinimized)){
				continue;
			}


//			renderImGuiDrawData(viewport.getDrawData(), viewportMap.get(handle));
			throw new NotImplementedException();
		}
	}
	
	
}
