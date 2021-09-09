module JDatablockFS.tools {
	requires java.desktop;
	requires org.joml;
	requires transitive JDatablockFS;
	requires jlapisutil;
	requires glfw.window;
	requires jlapisvector;
	requires org.lwjgl.glfw;
	requires org.lwjgl.opengl;
	requires org.lwjgl.stb;
	requires gson;
	
	exports com.lapissea.cfs.tools;
	exports com.lapissea.cfs.tools.logging;
}