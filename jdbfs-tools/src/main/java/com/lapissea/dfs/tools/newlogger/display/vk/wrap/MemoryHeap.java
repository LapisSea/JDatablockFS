package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkMemoryHeapFlag;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public class MemoryHeap{
	
	public final Set<VkMemoryHeapFlag> flags;
	public final long                  size;
	
	public MemoryHeap(int flags, long size){
		this(VkMemoryHeapFlag.from(flags), size);
	}
	public MemoryHeap(EnumSet<VkMemoryHeapFlag> flags, long size){
		this.flags = Collections.unmodifiableSet(flags);
		this.size = size;
	}
}
