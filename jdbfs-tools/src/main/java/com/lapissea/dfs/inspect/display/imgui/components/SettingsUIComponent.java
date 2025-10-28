package com.lapissea.dfs.inspect.display.imgui.components;

import com.lapissea.dfs.inspect.SessionSetView;
import com.lapissea.dfs.inspect.display.DeviceGC;
import com.lapissea.dfs.inspect.display.TextureRegistry;
import com.lapissea.dfs.inspect.display.VulkanDisplay;
import com.lapissea.dfs.inspect.display.imgui.UIComponent;
import com.lapissea.dfs.inspect.display.vk.enums.VkSampleCountFlag;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.TextUtil;
import imgui.ImGui;
import imgui.flag.ImGuiKey;
import imgui.type.ImBoolean;
import imgui.type.ImInt;
import imgui.type.ImString;

import java.util.Optional;

public final class SettingsUIComponent implements UIComponent{
	
	public record SessionRange(int[] currentFrame, ImInt start, ImInt end){
		
		public int getEnd(int frameCount){
			var rEnd = end.get();
			if(rEnd == -1) rEnd = frameCount;
			return rEnd;
		}
		public int getStart(){
			var rStart = start.get();
			if(rStart == -1) rStart = 1;
			return rStart;
		}
		public int getCurrentFrame(){
			return currentFrame[0];
		}
		public void setCurrentFrame(int currentFrame){
			this.currentFrame[0] = currentFrame;
		}
		public void frameDelta(int totalFrameCount, int delta){
			var rStart        = getStart();
			var rEnd          = getEnd(totalFrameCount);
			var newFrameIndex = getCurrentFrame() + delta;
			if(delta>0){
				setCurrentFrame(Math.min(newFrameIndex, rEnd));
			}else{
				setCurrentFrame(Math.max(newFrameIndex, rStart));
			}
		}
	}
	
	public final VulkanDisplay vulkanDisplay;
	public final ImInt         fpsLimit                = new ImInt(60);
	public final int[]         statsLevel              = {0};
	public final ImBoolean     imageViewerOpen         = new ImBoolean();
	public final ImBoolean     byteGridOpen            = new ImBoolean(true);
	public final ImBoolean     messagesOpen            = new ImBoolean(true);
	public final ImBoolean     frameStacktraceOpen     = new ImBoolean(true);
	public final ImInt         byteGridSampleEnumIndex = new ImInt(2);
	public final ImString      currentSessionName      = new ImString();
	public final SessionRange  currentSessionRange     = new SessionRange(new int[1], new ImInt(-1), new ImInt(-1));
	
	public boolean lastFrame = true;
	
	public String frameStacktrace;
	
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
		
		var optSession = currentSessionView();
		if(optSession.isPresent()){
			ImGui.separator();
			sessionRangeUI(optSession.get());
			
			ImGui.separator();
			
			if(enableButton("Frame stacktrace", frameStacktraceOpen.get())){
				frameStacktraceOpen.set(true);
			}
			if(frameStacktrace != null && !frameStacktrace.isBlank() && frameStacktraceOpen.get()){
				if(ImGui.begin("Frame Stacktrace", frameStacktraceOpen)){
					ImGui.text(frameStacktrace);
				}
				ImGui.end();
			}
		}
	}
	
	public Optional<SessionSetView.SessionView> currentSessionView(){
		var sesName = currentSessionName.get();
		return vulkanDisplay.sessionSetView.getSession(sesName);
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
		ImGui.sameLine();
		if(enableButton("Messages", messagesOpen.get())){
			messagesOpen.set(true);
		}
	}
	
	private void sessionRangeUI(SessionSetView.SessionView ses){
		ImGui.text("Session range:");
		
		var updated    = false;
		var frameCount = ses.frameCount();
		
		var rStart = currentSessionRange.getStart();
		var rEnd   = currentSessionRange.getEnd(frameCount);
		
		if(ImGui.sliderScalar("Frame", currentSessionRange.currentFrame, rStart, rEnd, (lastFrame? "*" : "") + "%d")){
			updated = true;
		}
		
		var delta = 0;
		if(ImGui.isItemFocused()){
			if(ImGui.isKeyPressed(ImGuiKey.LeftArrow, true)) delta--;
			if(ImGui.isKeyPressed(ImGuiKey.RightArrow, true)) delta++;
		}
		
		if(delta != 0){
			updated = true;
			currentSessionRange.frameDelta(frameCount, delta);
		}
		
		var rangeStart          = currentSessionRange.start;
		var rangeEnd            = currentSessionRange.end;
		var currentSessionFrame = currentSessionRange.currentFrame;
		
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
				vulkanDisplay.setFrameData(Optional.of(ses), currentSessionFrame[0]);
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
