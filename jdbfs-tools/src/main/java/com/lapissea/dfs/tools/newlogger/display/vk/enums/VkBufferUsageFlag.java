package com.lapissea.dfs.tools.newlogger.display.vk.enums;

import com.lapissea.dfs.tools.newlogger.display.VUtils;
import com.lapissea.dfs.tools.newlogger.display.vk.Flags;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

public enum VkBufferUsageFlag implements VUtils.FlagSetValue{
	TRANSFER_SRC(VK_BUFFER_USAGE_TRANSFER_SRC_BIT),
	TRANSFER_DST(VK_BUFFER_USAGE_TRANSFER_DST_BIT),
	UNIFORM_TEXEL_BUFFER(VK_BUFFER_USAGE_UNIFORM_TEXEL_BUFFER_BIT),
	STORAGE_TEXEL_BUFFER(VK_BUFFER_USAGE_STORAGE_TEXEL_BUFFER_BIT),
	UNIFORM_BUFFER(VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT),
	STORAGE_BUFFER(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT),
	INDEX_BUFFER(VK_BUFFER_USAGE_INDEX_BUFFER_BIT),
	VERTEX_BUFFER(VK_BUFFER_USAGE_VERTEX_BUFFER_BIT),
	INDIRECT_BUFFER(VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT),
	SHADER_DEVICE_ADDRESS(VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT),
	;
	
	public final int bit;
	VkBufferUsageFlag(int bit){ this.bit = bit; }
	
	@Override
	public int bit(){ return bit; }
	
	public static Flags<VkBufferUsageFlag> from(int props){ return new Flags<>(VkBufferUsageFlag.class, props); }
}
