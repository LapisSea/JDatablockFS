package com.lapissea.dfs.tools.newlogger.display.vk.enums;

import com.lapissea.dfs.tools.newlogger.display.VUtils;
import com.lapissea.dfs.tools.newlogger.display.vk.Flags;

import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_DEPTH_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_METADATA_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_STENCIL_BIT;

public enum VkImageAspectFlagBits implements VUtils.FlagSetValue{
	COLOR_BIT(VK_IMAGE_ASPECT_COLOR_BIT),
	DEPTH_BIT(VK_IMAGE_ASPECT_DEPTH_BIT),
	STENCIL_BIT(VK_IMAGE_ASPECT_STENCIL_BIT),
	METADATA_BIT(VK_IMAGE_ASPECT_METADATA_BIT),
	;
	
	public final int bit;
	VkImageAspectFlagBits(int bit){ this.bit = bit; }
	
	@Override
	public int bit(){ return bit; }
	
	public static Flags<VkImageAspectFlagBits> from(int props){ return new Flags<>(VkImageAspectFlagBits.class, props); }
}
