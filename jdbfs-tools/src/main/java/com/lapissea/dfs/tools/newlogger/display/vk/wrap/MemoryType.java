package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkMemoryPropertyFlags;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public class MemoryType{
	
	public final int                        heapIndex;
	public final Set<VkMemoryPropertyFlags> propertyFlags;
	
	public MemoryType(int heapIndex, int propertyFlags){
		this(heapIndex, VkMemoryPropertyFlags.from(propertyFlags));
	}
	public MemoryType(int heapIndex, EnumSet<VkMemoryPropertyFlags> propertyFlags){
		this.heapIndex = heapIndex;
		this.propertyFlags = Collections.unmodifiableSet(propertyFlags);
	}
}
