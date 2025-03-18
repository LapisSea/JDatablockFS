package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.vk.Flags;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkMemoryPropertyFlags;
import org.lwjgl.vulkan.VkMemoryType;

public class MemoryType{
	
	public final int                          heapIndex;
	public final Flags<VkMemoryPropertyFlags> propertyFlags;
	
	public MemoryType(VkMemoryType val){
		this(val.heapIndex(), new Flags<>(VkMemoryPropertyFlags.class, val.propertyFlags()));
	}
	public MemoryType(int heapIndex, Flags<VkMemoryPropertyFlags> propertyFlags){
		this.heapIndex = heapIndex;
		this.propertyFlags = propertyFlags;
	}
}
