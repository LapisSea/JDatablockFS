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
	requires org.lwjgl.vulkan;
	requires org.lwjgl.shaderc;
	requires org.lwjgl.stb;
	
	exports com.lapissea.dfs.tools;
	exports com.lapissea.dfs.tools.logging;
	exports com.lapissea.dfs.tools.server;
	exports com.lapissea.dfs.tools.utils;
	exports com.lapissea.dfs.tools.newlogger;
	
	opens com.lapissea.dfs.tools;
	exports com.lapissea.dfs.tools.newlogger.display.vk to jlapisutil;
	exports com.lapissea.dfs.tools.newlogger.display.vk.enums to jlapisutil;
	exports com.lapissea.dfs.tools.newlogger.display.vk.wrap to jlapisutil;
	exports com.lapissea.dfs.tools.newlogger.display to jlapisutil;
}
