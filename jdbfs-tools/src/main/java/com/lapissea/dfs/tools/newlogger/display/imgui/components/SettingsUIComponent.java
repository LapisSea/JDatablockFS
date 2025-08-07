package com.lapissea.dfs.tools.newlogger.display.imgui.components;

import com.lapissea.dfs.tools.newlogger.SessionSetView;
import com.lapissea.dfs.tools.newlogger.display.DeviceGC;
import com.lapissea.dfs.tools.newlogger.display.TextureRegistry;
import com.lapissea.dfs.tools.newlogger.display.VulkanDisplay;
import com.lapissea.dfs.tools.newlogger.display.imgui.UIComponent;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkSampleCountFlag;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.TextUtil;
import imgui.ImGui;
import imgui.flag.ImGuiKey;
import imgui.type.ImBoolean;
import imgui.type.ImInt;
import imgui.type.ImString;

public final class SettingsUIComponent implements UIComponent{
	
	private final VulkanDisplay vulkanDisplay;
	public final  ImInt         fpsLimit                = new ImInt(60);
	public final  int[]         statsLevel              = {0};
	public final  ImBoolean     imageViewerOpen         = new ImBoolean();
	public final  ImBoolean     byteGridOpen            = new ImBoolean(true);
	public final  ImInt         byteGridSampleEnumIndex = new ImInt(2);
	public final  ImString      currentSessionName      = new ImString();
	public final  int[]         currentSessionFrame     = new int[1];
	public final  ImInt         rangeStart              = new ImInt(-1);
	public final  ImInt         rangeEnd                = new ImInt(-1);
	
	public boolean lastFrame = true;
	
	VkSampleCountFlag[] samplesSet;
	public SettingsUIComponent(VulkanDisplay vulkanDisplay){ this.vulkanDisplay = vulkanDisplay; }
	
	@Override
	public void imRender(DeviceGC deviceGC, TextureRegistry.Scope tScope){
		if(ImGui.begin("Settings")){
			
			generalSettingsUI();
			ImGui.separator();
			
			if(byteGridOpen.get()){
				byteGridUI();
				ImGui.separator();
			}
		}
		ImGui.end();
	}
	
	private void byteGridUI(){
		ImGui.text("Byte grid:");
		if(ImGui.beginCombo("Sample count", sampleName(byteGridSampleEnumIndex.get()))){
			for(int i = 0; i<samplesSet.length; i++){
				var selected = byteGridSampleEnumIndex.get() == i;
				if(ImGui.selectable(sampleName(i), selected)){
					byteGridSampleEnumIndex.set(i);
				}
				if(selected) ImGui.setItemDefaultFocus();
			}
			ImGui.endCombo();
		}
		
		if(ImGui.beginCombo("Session", currentSessionName.get())){
			for(String name : vulkanDisplay.sessionSetView.getSessionNames()){
				var selected = currentSessionName.get().equals(name);
				if(ImGui.selectable(name, selected)){
					currentSessionName.set(name);
					vulkanDisplay.forceSessionUpdate = true;
				}
				if(selected) ImGui.setItemDefaultFocus();
			}
			ImGui.endCombo();
		}
		
		var sesName    = currentSessionName.get();
		var optSession = vulkanDisplay.sessionSetView.getSession(sesName);
		if(optSession.isPresent()){
			ImGui.separator();
			sessionRangeUI(optSession.get());
		}
	}
	
	private void generalSettingsUI(){
		ImGui.text("General settings:");
		ImGui.inputInt("FPS Limit", fpsLimit);
		if(fpsLimit.get()<0) fpsLimit.set(0);
		ImGui.sliderScalar("##FPS Limit", fpsLimit.getData(), 0, 300);
		ImGui.separator();
		
		ImGui.sliderScalar("View Stats", statsLevel, 0, 2);
		ImGui.separator();
		
		if(enableButton("Image viewer", imageViewerOpen.get())){
			imageViewerOpen.set(true);
		}
		ImGui.sameLine();
		if(enableButton("Byte grid viewer", byteGridOpen.get())){
			byteGridOpen.set(true);
		}
	}
	
	private void sessionRangeUI(SessionSetView.SessionView ses){
		ImGui.text("Session range:");
		
		var updated    = false;
		var frameCount = ses.frameCount();
		
		var rStart = rangeStart.get();
		if(rStart == -1) rStart = 1;
		var rEnd = rangeEnd.get();
		if(rEnd == -1) rEnd = frameCount;
		
		if(ImGui.sliderScalar("Frame", currentSessionFrame, rStart, rEnd, (lastFrame? "*" : "") + "%d")){
			updated = true;
		}
		if(ImGui.isItemFocused()){
			if(ImGui.isKeyPressed(ImGuiKey.LeftArrow, true) && currentSessionFrame[0]>rStart){
				currentSessionFrame[0]--;
				updated = true;
			}
			if(ImGui.isKeyPressed(ImGuiKey.RightArrow, true) && currentSessionFrame[0]<rEnd){
				currentSessionFrame[0]++;
				updated = true;
			}
		}
		
		if(frameCount>1){
			var snap = rangeStart.get() == currentSessionFrame[0];
			if(ImGui.inputInt("Range start", rangeStart) && snap && rangeStart.get()>0){
				currentSessionFrame[0] = rangeStart.get();
				updated = true;
			}
			if(rangeStart.get() != -1){
				if(rangeStart.get()<1) rangeStart.set(1);
				else if(rangeStart.get()>frameCount) rangeStart.set(frameCount);
			}
			
			snap = rangeEnd.get() == currentSessionFrame[0];
			if(ImGui.inputInt("Range end", rangeEnd) && snap && rangeEnd.get()>rangeStart.get()){
				currentSessionFrame[0] = rangeEnd.get();
				updated = true;
			}
			if(rangeEnd.get() != -1){
				if(rangeEnd.get()<rangeStart.get()) rangeEnd.set(rangeStart.get());
				else if(rangeEnd.get()>frameCount) rangeEnd.set(frameCount);
			}
			
			if(ImGui.button("Set start")){
				rangeStart.set(currentSessionFrame[0]);
			}
			ImGui.sameLine();
			if(enableButton("Set end", lastFrame)){
				rangeEnd.set(currentSessionFrame[0]);
			}
			
			if(updated){
				lastFrame = frameCount == currentSessionFrame[0];
				vulkanDisplay.setFrameData(ses.getFrameData(currentSessionFrame[0]));
			}
		}else{
			rangeStart.set(-1);
			rangeEnd.set(-1);
		}
	}
	
	private boolean enableButton(String label, boolean disabled){
		ImGui.beginDisabled(disabled);
		var clicked = ImGui.button(label);
		ImGui.endDisabled();
		return clicked;
	}
	
	public void setMaxSample(VkSampleCountFlag max){
		
		samplesSet = Iters.from(VkSampleCountFlag.class)
		                  .takeWhile(e -> e.ordinal()<=max.ordinal())
		                  .toArray(VkSampleCountFlag[]::new);
		
		byteGridSampleEnumIndex.set(Math.min(byteGridSampleEnumIndex.get(), samplesSet.length - 1));
	}
	
	private String sampleName(int cid){
		return samplesSet[cid].name().substring(1) + " " + TextUtil.plural("sample", cid + 1);
	}
	@Override
	public void unload(TextureRegistry.Scope tScope){ }
}
