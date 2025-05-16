package com.lapissea.dfs.tools.newlogger.display.vk.enums;

import com.lapissea.dfs.tools.newlogger.display.VUtils;

import static org.lwjgl.vulkan.VK10.VK_INDEX_TYPE_UINT16;
import static org.lwjgl.vulkan.VK10.VK_INDEX_TYPE_UINT32;
import static org.lwjgl.vulkan.VK14.VK_INDEX_TYPE_UINT8;

public enum VkIndexType implements VUtils.IDValue{
	UINT16(VK_INDEX_TYPE_UINT16),
	UINT32(VK_INDEX_TYPE_UINT32),
	UINT8(VK_INDEX_TYPE_UINT8),
	;
	
	public final int id;
	VkIndexType(int id){ this.id = id; }
	@Override
	public int id(){ return id; }
	
	public static VkIndexType from(int id){ return VUtils.fromID(VkIndexType.class, id); }
}
