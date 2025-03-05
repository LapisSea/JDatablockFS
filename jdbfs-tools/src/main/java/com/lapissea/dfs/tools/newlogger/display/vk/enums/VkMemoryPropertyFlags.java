package com.lapissea.dfs.tools.newlogger.display.vk.enums;

import com.lapissea.util.UtilL;

import java.util.EnumSet;

import static org.lwjgl.vulkan.VK10.*;

public enum VkMemoryPropertyFlags{
	DEVICE_LOCAL(VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT),
	HOST_VISIBLE(VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT),
	HOST_COHERENT(VK_MEMORY_PROPERTY_HOST_COHERENT_BIT),
	HOST_CACHED(VK_MEMORY_PROPERTY_HOST_CACHED_BIT),
	LAZILY_ALLOCATED(VK_MEMORY_PROPERTY_LAZILY_ALLOCATED_BIT),
	;
	public final int bit;
	VkMemoryPropertyFlags(int bit){ this.bit = bit; }
	
	public static EnumSet<VkMemoryPropertyFlags> from(int props){
		var flags = EnumSet.allOf(VkMemoryPropertyFlags.class);
		flags.removeIf(flag -> !UtilL.checkFlag(props, flag.bit));
		return flags;
	}
	
}
