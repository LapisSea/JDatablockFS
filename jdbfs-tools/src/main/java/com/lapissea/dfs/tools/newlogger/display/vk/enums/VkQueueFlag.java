package com.lapissea.dfs.tools.newlogger.display.vk.enums;

import com.lapissea.dfs.tools.newlogger.display.VUtils;
import com.lapissea.dfs.tools.newlogger.display.vk.Flags;

import static org.lwjgl.vulkan.KHRVideoDecodeQueue.VK_QUEUE_VIDEO_DECODE_BIT_KHR;
import static org.lwjgl.vulkan.KHRVideoEncodeQueue.VK_QUEUE_VIDEO_ENCODE_BIT_KHR;
import static org.lwjgl.vulkan.NVOpticalFlow.VK_QUEUE_OPTICAL_FLOW_BIT_NV;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_COMPUTE_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_GRAPHICS_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_SPARSE_BINDING_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_TRANSFER_BIT;
import static org.lwjgl.vulkan.VK11.VK_QUEUE_PROTECTED_BIT;

public enum VkQueueFlag implements VUtils.FlagSetValue{
	GRAPHICS(VK_QUEUE_GRAPHICS_BIT),
	COMPUTE(VK_QUEUE_COMPUTE_BIT),
	TRANSFER(VK_QUEUE_TRANSFER_BIT),
	SPARSE_BINDING(VK_QUEUE_SPARSE_BINDING_BIT),
	PROTECTED(VK_QUEUE_PROTECTED_BIT),
	VIDEO_DECODE_KHR(VK_QUEUE_VIDEO_DECODE_BIT_KHR),
	VIDEO_ENCODE_KHR(VK_QUEUE_VIDEO_ENCODE_BIT_KHR),
	OPTICAL_FLOW_NV(VK_QUEUE_OPTICAL_FLOW_BIT_NV),
	;
	
	public final int bit;
	VkQueueFlag(int bit){ this.bit = bit; }
	
	@Override
	public int bit(){ return bit; }
	
	public static Flags<VkQueueFlag> from(int props){ return new Flags<>(VkQueueFlag.class, props); }
}
