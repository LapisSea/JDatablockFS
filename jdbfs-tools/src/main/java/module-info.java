module JDatablockFS.tools {
	requires java.desktop;
	requires org.joml;
	requires JDatablockFS.core;
	requires jlapisutil;
	requires GLFWWindow;
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
	requires com.carrotsearch.hppc;
	requires com.sun.jna;
	
	exports com.lapissea.dfs.tools;
	exports com.lapissea.dfs.tools.logging;
	exports com.lapissea.dfs.tools.server;
	exports com.lapissea.dfs.tools.utils;
	
	
	opens com.lapissea.dfs.tools;
	opens com.lapissea.dfs.inspect.display to com.google.gson;
	exports com.lapissea.dfs.inspect.display.vk.enums to jlapisutil, JDatablockFS.run;
	exports com.lapissea.dfs.inspect.display.vk.wrap to jlapisutil, JDatablockFS.run;
	exports com.lapissea.dfs.inspect.display to jlapisutil, JDatablockFS.run;
	exports com.lapissea.dfs.inspect.display.renderers to jlapisutil;
	opens com.lapissea.dfs.inspect.display.renderers to com.google.gson;
	exports com.lapissea.dfs.inspect;
	exports com.lapissea.dfs.inspect.display.vk;
	opens com.lapissea.dfs.inspect.display.vk;
	exports com.lapissea.dfs.inspect.display.grid to jlapisutil;
	opens com.lapissea.dfs.inspect.display.grid to com.google.gson;
}
