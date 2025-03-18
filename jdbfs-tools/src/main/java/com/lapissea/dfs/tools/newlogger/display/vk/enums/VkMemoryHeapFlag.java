package com.lapissea.dfs.tools.newlogger.display.vk.enums;

import com.lapissea.dfs.tools.newlogger.display.VUtils;
import com.lapissea.dfs.tools.newlogger.display.vk.Flags;

import static org.lwjgl.vulkan.VK10.VK_MEMORY_HEAP_DEVICE_LOCAL_BIT;
import static org.lwjgl.vulkan.VK11.VK_MEMORY_HEAP_MULTI_INSTANCE_BIT;

public enum VkMemoryHeapFlag implements VUtils.FlagSetValue{
	DEVICE_LOCAL_BIT(VK_MEMORY_HEAP_DEVICE_LOCAL_BIT),
	MULTI_INSTANCE_BIT(VK_MEMORY_HEAP_MULTI_INSTANCE_BIT),
	;
	public final int bit;
	VkMemoryHeapFlag(int bit){
		this.bit = bit;
	}
	
	@Override
	public int bit(){ return bit; }
	
	public static Flags<VkMemoryHeapFlag> from(int props){ return new Flags<>(VkMemoryHeapFlag.class, props); }
}
