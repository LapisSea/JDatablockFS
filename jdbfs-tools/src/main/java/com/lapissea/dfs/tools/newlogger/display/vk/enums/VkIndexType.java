package com.lapissea.dfs.tools.newlogger.display.vk.enums;

import com.lapissea.dfs.tools.newlogger.display.VUtils;

import static org.lwjgl.vulkan.VK10.VK_INDEX_TYPE_UINT16;
import static org.lwjgl.vulkan.VK10.VK_INDEX_TYPE_UINT32;
import static org.lwjgl.vulkan.VK14.VK_INDEX_TYPE_UINT8;

public enum VkIndexType implements VUtils.IDValue{
	UINT16(VK_INDEX_TYPE_UINT16, 2),
	UINT32(VK_INDEX_TYPE_UINT32, 4),
	UINT8(VK_INDEX_TYPE_UINT8, 1),
	;
	
	public final int id;
	public final int byteSize;
	
	VkIndexType(int id, int byteSize){
		this.id = id;
		this.byteSize = byteSize;
	}
	
	@Override
	public int id(){ return id; }
	
	public static VkIndexType from(int id){ return VUtils.fromID(VkIndexType.class, id); }
}
