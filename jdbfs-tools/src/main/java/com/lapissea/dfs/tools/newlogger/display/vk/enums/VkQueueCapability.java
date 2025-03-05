package com.lapissea.dfs.tools.newlogger.display.vk.enums;

import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.UtilL;

import java.util.List;

import static org.lwjgl.vulkan.VK10.VK_QUEUE_COMPUTE_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_GRAPHICS_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_SPARSE_BINDING_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_TRANSFER_BIT;

public enum VkQueueCapability{
	GRAPHICS(VK_QUEUE_GRAPHICS_BIT),
	COMPUTE(VK_QUEUE_COMPUTE_BIT),
	TRANSFER(VK_QUEUE_TRANSFER_BIT),
	SPARSE_BINDING(VK_QUEUE_SPARSE_BINDING_BIT),
	;
	
	public final int bit;
	VkQueueCapability(int bit){ this.bit = bit; }
	
	public static List<VkQueueCapability> from(int props){
		return Iters.from(VkQueueCapability.class).filter(cap -> UtilL.checkFlag(props, cap.bit)).toList();
	}
}
