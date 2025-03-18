package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.vk.Flags;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkMemoryHeapFlag;
import org.lwjgl.vulkan.VkMemoryHeap;

public class MemoryHeap{
	
	public final Flags<VkMemoryHeapFlag> flags;
	public final long                    size;
	
	public MemoryHeap(VkMemoryHeap val){
		this(new Flags<>(VkMemoryHeapFlag.class, val.flags()), val.size());
	}
	public MemoryHeap(Flags<VkMemoryHeapFlag> flags, long size){
		this.flags = flags;
		this.size = size;
	}
}
