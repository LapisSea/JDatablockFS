package com.lapissea.dfs.tools.newlogger.display.vk.enums;

import com.lapissea.dfs.tools.newlogger.display.VUtils;

import static org.lwjgl.vulkan.VK10.VK_POLYGON_MODE_FILL;
import static org.lwjgl.vulkan.VK10.VK_POLYGON_MODE_LINE;
import static org.lwjgl.vulkan.VK10.VK_POLYGON_MODE_POINT;

public enum VkPolygonMode implements VUtils.IDValue{
	FILL(VK_POLYGON_MODE_FILL),
	LINE(VK_POLYGON_MODE_LINE),
	POINT(VK_POLYGON_MODE_POINT),
	;
	
	public final int id;
	VkPolygonMode(int id){ this.id = id; }
	
	@Override
	public int id(){ return id; }
	
	public static VkPolygonMode from(int props){
		return VUtils.fromID(VkPolygonMode.class, props);
	}
}
