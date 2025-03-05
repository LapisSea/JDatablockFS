package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkColorSpaceKHR;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkFormat;

public class SurfaceFormat{
	
	public final VkFormat        format;
	public final VkColorSpaceKHR colorSpace;
	
	public SurfaceFormat(int format, int colorSpace){
		this(VkFormat.from(format), VkColorSpaceKHR.from(colorSpace));
	}
	public SurfaceFormat(VkFormat format, VkColorSpaceKHR colorSpace){
		this.format = format;
		this.colorSpace = colorSpace;
	}
}
