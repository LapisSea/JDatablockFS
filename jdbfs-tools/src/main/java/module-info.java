module JDatablockFS.tools {
	requires java.desktop;
	requires org.joml;
	requires JDatablockFS.core;
	requires jlapisutil;
	requires glfw.window;
	requires jlapisvector;
	requires org.lwjgl.glfw;
	requires org.lwjgl.opengl;
	requires com.google.gson;
	requires com.esotericsoftware.kryo;
	requires roaringbitmap;
	
	requires imgui.lwjgl3;
	requires imgui.binding;
	
	exports com.lapissea.dfs.tools;
	exports com.lapissea.dfs.tools.logging;
	exports com.lapissea.dfs.tools.server;
	exports com.lapissea.dfs.tools.utils;
	exports com.lapissea.dfs.tools.newlogger;
	
	opens com.lapissea.dfs.tools;
}
