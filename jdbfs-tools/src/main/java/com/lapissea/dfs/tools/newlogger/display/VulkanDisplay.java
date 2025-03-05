package com.lapissea.dfs.tools.newlogger.display;

import com.lapissea.dfs.tools.newlogger.display.vk.VulkanCore;
import com.lapissea.glfw.GlfwKeyboardEvent;
import com.lapissea.glfw.GlfwWindow;
import com.lapissea.util.LogUtil;
import com.lapissea.util.UtilL;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.Configuration;

import java.io.File;

import static com.lapissea.dfs.tools.newlogger.display.VUtils.createVulkanIcon;

public class VulkanDisplay implements AutoCloseable{
	
	static{
		Configuration.DEBUG.set(true);
		LogUtil.Init.attach(LogUtil.Init.USE_CALL_THREAD);
	}
	
	private GlfwWindow window;
	private VulkanCore vkCore;
	
	public VulkanDisplay(){ }
	
	public void init(){
		window = createWindow();
		window.registryKeyboardKey.register(GLFW.GLFW_KEY_ESCAPE, GlfwKeyboardEvent.Type.DOWN, e -> window.requestClose());
		vkCore = new VulkanCore("DFS debugger", window);
	}
	
	private GlfwWindow createWindow(){
		var win = new GlfwWindow();
		win.title.set("DFS visual debugger");
		win.size.set(800, 600);
		win.centerWindow();
		
		var winFile = new File("winRemember.json");
		win.loadState(winFile);
		win.autoHandleStateSaving(winFile);
		
		win.init(i -> i.withVulkan(v -> v.withVersion(VulkanCore.API_VERSION_MAJOR, VulkanCore.API_VERSION_MINOR)).resizeable(false));
		Thread.ofVirtual().start(() -> win.setIcon(createVulkanIcon(128, 128)));
		return win;
	}
	
	public void run(){
		window.show();
		while(!window.shouldClose()){
			UtilL.sleep(2);
			window.pollEvents();
		}
	}
	@Override
	public void close(){
		window.hide();
		vkCore.close();
		window.destroy();
	}
	
}
