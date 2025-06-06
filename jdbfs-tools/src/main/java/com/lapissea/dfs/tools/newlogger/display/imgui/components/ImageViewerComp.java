package com.lapissea.dfs.tools.newlogger.display.imgui.components;

import com.lapissea.dfs.tools.newlogger.display.ColorRGBA8;
import com.lapissea.dfs.tools.newlogger.display.TextureRegistry;
import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.imgui.UIComponent;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkFormat;
import imgui.ImGui;
import imgui.flag.ImGuiInputTextFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.type.ImBoolean;
import imgui.type.ImString;

import java.io.File;
import java.io.IOException;

public final class ImageViewerComp implements UIComponent{
	
	private sealed interface State{
		record Ok(long id) implements State{ }
		
		record Error(String message) implements State{ }
	}
	
	private State state = new State.Error("No file loaded");
	private File  file;
	
	private final ImString imFile = new ImString();
	
	private final ImBoolean open;
	public ImageViewerComp(ImBoolean open){
		this.open = open;
	}
	
	@Override
	public void unload(TextureRegistry.Scope tScope){
		if(state instanceof State.Ok(var id)){
			tScope.releaseTexture(id);
		}
		state = new State.Error("No file loaded");
		file = null;
	}
	
	@SuppressWarnings("DataFlowIssue")
	@Override
	public void imRender(TextureRegistry.Scope tScope){
		if(!open.get()) return;
		
		ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0, 0);
		if(ImGui.begin("Image viewer", open)){
			if(ImGui.inputText("Image path", imFile, ImGuiInputTextFlags.AutoSelectAll)){
				var f = new File(imFile.get());
				if(!f.equals(file)){
					unload(tScope);
					file = f;
					try{
						state = new State.Ok(load(f, tScope));
					}catch(Throwable e){
						state = new State.Error("Failed to load image file:\n" + e);
					}
				}
			}
			switch(state){
				case State.Error(var msg) -> {
					ImGui.textWrapped(msg);
				}
				case State.Ok(var id) -> {
					float viewWidth  = ImGui.getContentRegionAvailX();
					float viewHeight = ImGui.getContentRegionAvailY();
					
					var   size   = tScope.registry().getTexture(id).image.extent;
					float width  = size.width;
					float height = size.height;
					
					var wScale = viewWidth/width;
					var hScale = viewHeight/height;
					var scale  = Math.min(wScale, hScale);
					width *= scale;
					height *= scale;
					
					var pos = ImGui.getCursorPos();
					pos.x += (viewWidth - width)/2;
					pos.y += (viewHeight - height)/2;
					ImGui.setCursorPos(pos);
					
					ImGui.image(id, width, height);
				}
			}
		}
		ImGui.end();
		ImGui.popStyleVar();
	}
	private long load(File f, TextureRegistry.Scope tScope) throws IOException, VulkanCodeException{
		try(var image = ColorRGBA8.fromFile(f)){
			return tScope.loadTextureAsID(image.width, image.height, image.getPixels(), VkFormat.R8G8B8A8_UNORM, 1);
		}
	}
}
