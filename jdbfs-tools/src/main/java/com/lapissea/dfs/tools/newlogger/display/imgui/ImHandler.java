package com.lapissea.dfs.tools.newlogger.display.imgui;

import com.lapissea.dfs.tools.newlogger.display.VulkanWindow;
import com.lapissea.dfs.tools.newlogger.display.renderers.ImGUIRenderer;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanCore;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkFormat;
import com.lapissea.glfw.BuffUtil;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.ImVec2;
import imgui.flag.ImGuiConfigFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;

import javax.imageio.ImageIO;
import java.io.File;
import java.nio.ByteBuffer;

public class ImHandler{
	
	private final ImGuiImpl     imGuiImpl;
	private final ImGUIRenderer imGuiRenderer;
	
	long id;
	
	public ImHandler(VulkanCore core, VulkanWindow window, ImGUIRenderer imGuiRenderer){
		this.imGuiRenderer = imGuiRenderer;
		
		ImGui.setCurrentContext(ImGui.createContext());
		
		ImGuiIO io = ImGui.getIO();
		io.addConfigFlags(ImGuiConfigFlags.DockingEnable|ImGuiConfigFlags.ViewportsEnable|
		                  ImGuiConfigFlags.NavEnableKeyboard|ImGuiConfigFlags.NavEnableSetMousePos);
		io.setConfigDockingTransparentPayload(true);
		
		
		ImTools.setupFont("/CourierPrime/Regular/font.ttf");
		
		imGuiImpl = new ImGuiImpl(core, imGuiRenderer);
		imGuiImpl.init(window, true);
		
		try{
			var image = ImageIO.read(new File("C:\\users\\lapissea\\Desktop\\test.png"));
			var buff  = ByteBuffer.allocate(image.getWidth()*image.getHeight()*4);
			BuffUtil.imageToBuffer(image, buff);
			
			id = core.textureRegistry.loadTextureAsID(image.getWidth(), image.getHeight(), buff, VkFormat.R8G8B8A8_UNORM, 1);
		}catch(Throwable e){
			throw new RuntimeException(e);
		}
	}
	
	public void doFrame(){
		imGuiRenderer.checkFonts();
		imGuiImpl.newFrame();
		ImGui.newFrame();
		renderImGUI();
		ImGui.render();
		ImGui.updatePlatformWindows();
	}
	
	
	private void renderImGUI(){
		ImGui.dockSpaceOverViewport(ImGui.getMainViewport());
		
		showFps();
		
		ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0, 0);
		if(ImGui.begin("LOLL")){
			ImGui.image(id, ImGui.getContentRegionAvailX(), ImGui.getContentRegionAvailY());
		}
		ImGui.end();
		ImGui.popStyleVar();
		
		ImGui.showDemoWindow();
		ImGui.showMetricsWindow();
	}
	
	private static void showFps(){
		ImGui.setNextWindowPos(ImGui.getMainViewport().getPos());
		
		int flags = ImGuiWindowFlags.NoDecoration|ImGuiWindowFlags.AlwaysAutoResize|
		            ImGuiWindowFlags.NoFocusOnAppearing|ImGuiWindowFlags.NoNav|ImGuiWindowFlags.NoDocking|ImGuiWindowFlags.NoInputs;
		ImGui.begin("FPS Overlay", new ImBoolean(true), flags|ImGuiWindowFlags.NoBackground);
		
		ImVec2 pos     = ImGui.getWindowPos();
		var    hovered = ImGui.isMouseHoveringRect(pos, pos.plus(ImGui.getWindowSize()));
		ImGui.textColored(hovered? 0x55FFFFFF : 0xFFFFFFFF, String.format("FPS: %.1f", ImGui.getIO().getFramerate()));
		ImGui.end();
	}
	
	public void close(){
		imGuiImpl.shutdown();
	}
	
}
