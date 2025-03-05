package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkExtent2D;

public class Extent2D{
	
	public final int width, height;
	
	public Extent2D(VkExtent2D extent){
		this(extent.width(), extent.height());
	}
	public Extent2D(int width, int height){
		this.width = width;
		this.height = height;
	}
	@Override
	public String toString(){
		return width + "x" + height;
	}
	public VkExtent2D toStack(MemoryStack mem){
		return VkExtent2D.malloc(mem).set(width, height);
	}
}
