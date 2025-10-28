package com.lapissea.dfs.inspect.display.vk.enums;

import com.lapissea.dfs.inspect.display.VUtils;
import com.lapissea.dfs.inspect.display.vk.Flags;

import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_DEPTH_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_METADATA_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_STENCIL_BIT;

public enum VkImageAspectFlag implements VUtils.FlagSetValue{
	COLOR(VK_IMAGE_ASPECT_COLOR_BIT),
	DEPTH(VK_IMAGE_ASPECT_DEPTH_BIT),
	STENCIL(VK_IMAGE_ASPECT_STENCIL_BIT),
	METADATA(VK_IMAGE_ASPECT_METADATA_BIT),
	;
	
	public final int bit;
	VkImageAspectFlag(int bit){ this.bit = bit; }
	
	@Override
	public int bit(){ return bit; }
	
	public static Flags<VkImageAspectFlag> from(int props){ return new Flags<>(VkImageAspectFlag.class, props); }
}
