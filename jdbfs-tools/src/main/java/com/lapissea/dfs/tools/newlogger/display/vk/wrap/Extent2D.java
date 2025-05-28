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
	
	public Extent3D as3d(){
		return new Extent3D(width, height, 1);
	}
	
	public Rect2D asRect(){
		return new Rect2D(width, height);
	}
	
	@Override
	public final boolean equals(Object o){
		return o instanceof Extent2D that && width == that.width && height == that.height;
	}
	@Override
	public int hashCode(){
		return 31*width + height;
	}
}
