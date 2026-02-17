module JDatablockFS.old_logger {
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
	requires IterablePP;
	
	exports com.lapissea.dfs.old_logger.logging;
	
	opens com.lapissea.dfs.old_logger;
}
