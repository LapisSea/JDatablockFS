package com.lapissea.dfs.inspect.display.vk.enums;

import com.lapissea.dfs.inspect.display.VUtils;

import static org.lwjgl.vulkan.VK10.VK_VERTEX_INPUT_RATE_INSTANCE;
import static org.lwjgl.vulkan.VK10.VK_VERTEX_INPUT_RATE_VERTEX;

public enum VkVertexInputRate implements VUtils.IDValue{
	
	VERTEX(VK_VERTEX_INPUT_RATE_VERTEX),
	INSTANCE(VK_VERTEX_INPUT_RATE_INSTANCE),
	;
	
	public final int id;
	VkVertexInputRate(int id){ this.id = id; }
	@Override
	public int id(){ return id; }
	
	public static VkVertexInputRate from(int id){ return VUtils.fromID(VkVertexInputRate.class, id); }
}
