package com.lapissea.dfs.tools.newlogger.display.vk;

public interface VulkanResource extends AutoCloseable{
	default void close(){ destroy(); }
	void destroy();
}
