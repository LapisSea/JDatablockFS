package com.lapissea.dfs.inspect.display.vk.wrap;

import com.lapissea.dfs.inspect.display.vk.Flags;
import com.lapissea.dfs.inspect.display.vk.enums.VkMemoryHeapFlag;
import org.lwjgl.vulkan.VkMemoryHeap;

public class MemoryHeap{
	
	public final Flags<VkMemoryHeapFlag> flags;
	public final long                    size;
	
	public MemoryHeap(VkMemoryHeap val){
		this(VkMemoryHeapFlag.from(val.flags()), val.size());
	}
	public MemoryHeap(Flags<VkMemoryHeapFlag> flags, long size){
		this.flags = flags;
		this.size = size;
	}
}
