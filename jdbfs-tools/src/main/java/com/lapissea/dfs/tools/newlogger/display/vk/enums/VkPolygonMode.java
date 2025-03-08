package com.lapissea.dfs.tools.newlogger.display.vk.enums;

import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.UtilL;

import java.util.List;

import static org.lwjgl.vulkan.VK10.VK_POLYGON_MODE_FILL;
import static org.lwjgl.vulkan.VK10.VK_POLYGON_MODE_LINE;
import static org.lwjgl.vulkan.VK10.VK_POLYGON_MODE_POINT;

public enum VkPolygonMode{
	FILL(VK_POLYGON_MODE_FILL),
	LINE(VK_POLYGON_MODE_LINE),
	POINT(VK_POLYGON_MODE_POINT),
	;
	
	public final int bit;
	VkPolygonMode(int bit){ this.bit = bit; }
	
	public static List<VkPolygonMode> from(int props){
		return Iters.from(VkPolygonMode.class).filter(cap -> UtilL.checkFlag(props, cap.bit)).toList();
	}
}
