package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.vk.Flags;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkMemoryPropertyFlag;
import org.lwjgl.vulkan.VkMemoryType;

public class MemoryType{
	
	public final int                         heapIndex;
	public final Flags<VkMemoryPropertyFlag> propertyFlags;
	
	public MemoryType(VkMemoryType val){
		this(val.heapIndex(), VkMemoryPropertyFlag.from(val.propertyFlags()));
	}
	public MemoryType(int heapIndex, Flags<VkMemoryPropertyFlag> propertyFlags){
		this.heapIndex = heapIndex;
		this.propertyFlags = propertyFlags;
	}
}
