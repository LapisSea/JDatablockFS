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
	requires RoaringBitmap;
	
	requires imgui.lwjgl3;
	requires imgui.binding;
	
	exports com.lapissea.cfs.tools;
	exports com.lapissea.cfs.tools.logging;
	exports com.lapissea.cfs.tools.server;
	exports com.lapissea.cfs.tools.utils;
	
	opens com.lapissea.cfs.tools;
	opens com.lapissea.cfs.tools.logging.session;
	exports com.lapissea.cfs.tools.logging.session;
}
