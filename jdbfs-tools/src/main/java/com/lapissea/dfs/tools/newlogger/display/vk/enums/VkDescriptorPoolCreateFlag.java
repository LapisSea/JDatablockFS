package com.lapissea.dfs.tools.newlogger.display.vk.enums;

import com.lapissea.dfs.tools.newlogger.display.VUtils;
import com.lapissea.dfs.tools.newlogger.display.vk.Flags;

import static org.lwjgl.vulkan.EXTMutableDescriptorType.VK_DESCRIPTOR_POOL_CREATE_HOST_ONLY_BIT_EXT;
import static org.lwjgl.vulkan.NVDescriptorPoolOverallocation.VK_DESCRIPTOR_POOL_CREATE_ALLOW_OVERALLOCATION_POOLS_BIT_NV;
import static org.lwjgl.vulkan.NVDescriptorPoolOverallocation.VK_DESCRIPTOR_POOL_CREATE_ALLOW_OVERALLOCATION_SETS_BIT_NV;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT;
import static org.lwjgl.vulkan.VK12.VK_DESCRIPTOR_POOL_CREATE_UPDATE_AFTER_BIND_BIT;

public enum VkDescriptorPoolCreateFlag implements VUtils.FlagSetValue{
	FREE_DESCRIPTOR_SET(VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT),
	UPDATE_AFTER_BIND(VK_DESCRIPTOR_POOL_CREATE_UPDATE_AFTER_BIND_BIT),
	HOST_ONLY_EXT(VK_DESCRIPTOR_POOL_CREATE_HOST_ONLY_BIT_EXT),
	ALLOW_OVERALLOCATION_SETS_NV(VK_DESCRIPTOR_POOL_CREATE_ALLOW_OVERALLOCATION_SETS_BIT_NV),
	ALLOW_OVERALLOCATION_POOLS_NV(VK_DESCRIPTOR_POOL_CREATE_ALLOW_OVERALLOCATION_POOLS_BIT_NV),
	;
	
	public final int bit;
	VkDescriptorPoolCreateFlag(int bit){ this.bit = bit; }
	
	@Override
	public int bit(){ return bit; }
	
	public static Flags<VkDescriptorPoolCreateFlag> from(int props){ return new Flags<>(VkDescriptorPoolCreateFlag.class, props); }
}
