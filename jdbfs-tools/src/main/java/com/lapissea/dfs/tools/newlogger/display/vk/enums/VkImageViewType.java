package com.lapissea.dfs.tools.newlogger.display.vk.enums;

import com.lapissea.dfs.tools.newlogger.display.VUtils;

import static org.lwjgl.vulkan.VK10.*;

public enum VkImageViewType implements VUtils.IDValue{
	TYPE_1D(VK_IMAGE_VIEW_TYPE_1D),
	TYPE_2D(VK_IMAGE_VIEW_TYPE_2D),
	TYPE_3D(VK_IMAGE_VIEW_TYPE_3D),
	TYPE_CUBE(VK_IMAGE_VIEW_TYPE_CUBE),
	TYPE_1D_ARRAY(VK_IMAGE_VIEW_TYPE_1D_ARRAY),
	TYPE_2D_ARRAY(VK_IMAGE_VIEW_TYPE_2D_ARRAY),
	TYPE_CUBE_ARRAY(VK_IMAGE_VIEW_TYPE_CUBE_ARRAY),
	;
	
	public final int id;
	VkImageViewType(int id){ this.id = id; }
	@Override
	public int id(){ return id; }
	
	public static VkImageViewType from(int id){ return VUtils.fromID(VkImageViewType.class, id); }
}
