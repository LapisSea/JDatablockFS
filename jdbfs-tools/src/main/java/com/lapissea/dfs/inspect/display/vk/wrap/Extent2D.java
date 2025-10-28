package com.lapissea.dfs.inspect.display.vk.wrap;

import com.lapissea.vec.interf.IVec2iR;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkExtent2D;

public final class Extent2D{
	
	public final int width, height;
	
	public Extent2D(IVec2iR size){
		this(size.x(), size.y());
	}
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
	public boolean equals(Object o){
		return o instanceof Extent2D that && equals(that.width, that.height);
	}
	public boolean equals(int width, int height){
		return this.width == width && this.height == height;
	}
	@Override
	public int hashCode(){
		return 31*width + height;
	}
}
