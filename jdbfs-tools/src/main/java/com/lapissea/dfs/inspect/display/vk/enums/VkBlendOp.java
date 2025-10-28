package com.lapissea.dfs.inspect.display.vk.enums;

import com.lapissea.dfs.inspect.display.VUtils;

import static org.lwjgl.vulkan.VK10.*;

public enum VkBlendOp implements VUtils.IDValue{
	ADD(VK_BLEND_OP_ADD),
	SUBTRACT(VK_BLEND_OP_SUBTRACT),
	REVERSE_SUBTRACT(VK_BLEND_OP_REVERSE_SUBTRACT),
	MIN(VK_BLEND_OP_MIN),
	MAX(VK_BLEND_OP_MAX),
	;
	
	public final int id;
	VkBlendOp(int id){ this.id = id; }
	@Override
	public int id(){ return id; }
	
	public static VkBlendOp from(int id){ return VUtils.fromID(VkBlendOp.class, id); }
}
