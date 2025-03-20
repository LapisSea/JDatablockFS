package com.lapissea.dfs.tools.newlogger.display.vk.enums;

import com.lapissea.dfs.tools.newlogger.display.VUtils;
import com.lapissea.dfs.tools.newlogger.display.vk.Flags;

import static org.lwjgl.vulkan.VK10.*;

public enum VkMemoryPropertyFlag implements VUtils.FlagSetValue{
	DEVICE_LOCAL(VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT),
	HOST_VISIBLE(VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT),
	HOST_COHERENT(VK_MEMORY_PROPERTY_HOST_COHERENT_BIT),
	HOST_CACHED(VK_MEMORY_PROPERTY_HOST_CACHED_BIT),
	LAZILY_ALLOCATED(VK_MEMORY_PROPERTY_LAZILY_ALLOCATED_BIT),
	;
	public final int bit;
	VkMemoryPropertyFlag(int bit){ this.bit = bit; }
	
	@Override
	public int bit(){ return bit; }
	
	public static Flags<VkMemoryPropertyFlag> from(int props){ return new Flags<>(VkMemoryPropertyFlag.class, props); }
	
}
