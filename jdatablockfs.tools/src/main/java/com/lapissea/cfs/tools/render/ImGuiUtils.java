package com.lapissea.cfs.tools.render;

import com.lapissea.cfs.tools.DisplayManager;
import imgui.ImGui;
import imgui.gl3.ImGuiImplGl3;

import java.util.Objects;

public class ImGuiUtils{
	
	public static void load(){}
	static{
		init();
	}
	
	private static synchronized void init(){
		try{
			System.setProperty("imgui.library.path", "./lib");
			ImGui.createContext();
			
			var    io=ImGui.getIO();
			var    f =io.getFonts();
			byte[] bb;
			try(var t=Objects.requireNonNull(DisplayManager.class.getResourceAsStream("/CourierPrime/Regular/font.ttf"))){
				bb=t.readAllBytes();
			}
			f.clearFonts();
//			f.addFontFromMemoryTTF(bb, 16);
			f.addFontFromMemoryTTF(bb, 16);
			f.build();
		}catch(Throwable e){
			e.printStackTrace();
			System.exit(1);
		}
	}
	
	public static synchronized ImGuiImplGl3 makeGL3Impl(){
		var impl=new ImGuiImplGl3();
		impl.init("#version 330 core");
		return impl;
	}
	public static synchronized ImGuiImplG2D makeG2DImpl(){
		var impl=new ImGuiImplG2D();
		impl.init();
		return impl;
	}
	
}
