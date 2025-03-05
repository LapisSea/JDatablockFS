package com.lapissea.dfs.tools.newlogger.display.vk.enums;

import java.util.EnumSet;

import static org.lwjgl.vulkan.VK10.VK_MEMORY_HEAP_DEVICE_LOCAL_BIT;
import static org.lwjgl.vulkan.VK11.VK_MEMORY_HEAP_MULTI_INSTANCE_BIT;

public enum VkMemoryHeapFlag{
	DEVICE_LOCAL_BIT(VK_MEMORY_HEAP_DEVICE_LOCAL_BIT),
	MULTI_INSTANCE_BIT(VK_MEMORY_HEAP_MULTI_INSTANCE_BIT),
	;
	public final int bit;
	VkMemoryHeapFlag(int bit){
		this.bit = bit;
	}
	
	public static EnumSet<VkMemoryHeapFlag> from(int props){
		var flags = EnumSet.allOf(VkMemoryHeapFlag.class);
		flags.removeIf(flag -> (flag.bit&props) == 0);
		return flags;
	}
	
}
