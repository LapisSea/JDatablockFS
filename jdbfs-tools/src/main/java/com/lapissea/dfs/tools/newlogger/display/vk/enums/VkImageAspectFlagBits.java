package com.lapissea.dfs.tools.newlogger.display.vk.enums;

import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.UtilL;

import java.util.List;

import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_DEPTH_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_METADATA_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_STENCIL_BIT;

public enum VkImageAspectFlagBits{
	COLOR_BIT(VK_IMAGE_ASPECT_COLOR_BIT),
	DEPTH_BIT(VK_IMAGE_ASPECT_DEPTH_BIT),
	STENCIL_BIT(VK_IMAGE_ASPECT_STENCIL_BIT),
	METADATA_BIT(VK_IMAGE_ASPECT_METADATA_BIT),
	;
	
	public final int bit;
	VkImageAspectFlagBits(int bit){ this.bit = bit; }
	
	public static List<VkImageAspectFlagBits> from(int props){
		return Iters.from(VkImageAspectFlagBits.class).filter(cap -> UtilL.checkFlag(props, cap.bit)).toList();
	}
}
