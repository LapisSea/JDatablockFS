package com.lapissea.dfs.tools.newlogger.display.vk.enums;

import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.UtilL;

import java.util.List;

import static org.lwjgl.vulkan.VK10.VK_CULL_MODE_BACK_BIT;
import static org.lwjgl.vulkan.VK10.VK_CULL_MODE_FRONT_AND_BACK;
import static org.lwjgl.vulkan.VK10.VK_CULL_MODE_FRONT_BIT;
import static org.lwjgl.vulkan.VK10.VK_CULL_MODE_NONE;

public enum VkCullModeFlag{
	NONE(VK_CULL_MODE_NONE),
	FRONT(VK_CULL_MODE_FRONT_BIT),
	BACK(VK_CULL_MODE_BACK_BIT),
	FRONT_AND_BACK(VK_CULL_MODE_FRONT_AND_BACK),
	;
	
	public final int bit;
	VkCullModeFlag(int bit){ this.bit = bit; }
	
	public static List<VkCullModeFlag> from(int props){
		return Iters.from(VkCullModeFlag.class).filter(cap -> UtilL.checkFlag(props, cap.bit)).toList();
	}
}
