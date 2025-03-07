package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import org.lwjgl.vulkan.VkRect2D;

public final class Rect2D{
	
	public final int x, y, width, height;
	
	public Rect2D(VkRect2D rect){
		var off    = rect.offset();
		var extent = rect.extent();
		
		this.x = off.x();
		this.y = off.y();
		this.width = extent.width();
		this.height = extent.height();
	}
	
	public Rect2D(Extent2D extent){
		this(0, 0, extent.width, extent.height);
	}
	public Rect2D(int width, int height){
		this(0, 0, width, height);
	}
	public Rect2D(int x, int y, int width, int height){
		this.x = x;
		this.y = y;
		this.width = width;
		this.height = height;
	}
	
	public VkRect2D set(VkRect2D rect){
		rect.offset().set(x, y);
		rect.extent().set(width, height);
		return rect;
	}
}
