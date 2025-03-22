package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkExtent3D;

public class Extent3D{
	
	public final int width, height, depth;
	
	public Extent3D(VkExtent3D extent){
		this(extent.width(), extent.height(), extent.depth());
	}
	public Extent3D(int width, int height, int depth){
		this.width = width;
		this.height = height;
		this.depth = depth;
	}
	
	@Override
	public String toString(){
		return width + "x" + height + "x" + depth;
	}
	public VkExtent3D toStack(MemoryStack mem){
		return VkExtent3D.malloc(mem).set(width, height, depth);
	}
	
	@Override
	public final boolean equals(Object o){
		return o instanceof Extent3D that && width == that.width && height == that.height && depth == that.depth;
	}
	@Override
	public int hashCode(){
		int result = width;
		result = 31*result + height;
		result = 31*result + depth;
		return result;
	}
}
