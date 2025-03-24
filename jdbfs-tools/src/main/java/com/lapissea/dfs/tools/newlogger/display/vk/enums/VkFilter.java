package com.lapissea.dfs.tools.newlogger.display.vk.enums;

import com.lapissea.dfs.tools.newlogger.display.VUtils;

import static org.lwjgl.vulkan.EXTFilterCubic.VK_FILTER_CUBIC_EXT;
import static org.lwjgl.vulkan.IMGFilterCubic.VK_FILTER_CUBIC_IMG;
import static org.lwjgl.vulkan.VK10.VK_FILTER_LINEAR;
import static org.lwjgl.vulkan.VK10.VK_FILTER_NEAREST;

public enum VkFilter implements VUtils.IDValue{
	NEAREST(VK_FILTER_NEAREST),
	LINEAR(VK_FILTER_LINEAR),
	CUBIC_EXT(VK_FILTER_CUBIC_EXT),
	CUBIC_IMG(VK_FILTER_CUBIC_IMG),
	;
	
	public final int id;
	VkFilter(int id){ this.id = id; }
	@Override
	public int id(){ return id; }
	
	public static VkFilter from(int id){ return VUtils.fromID(VkFilter.class, id); }
}
