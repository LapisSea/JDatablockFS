package com.lapissea.dfs.inspect.display.vk.enums;

import com.lapissea.dfs.inspect.display.VUtils;

import static org.lwjgl.vulkan.VK10.VK_IMAGE_TYPE_1D;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_TYPE_2D;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_TYPE_3D;

public enum VKImageType implements VUtils.IDValue{
	IMG_1D(VK_IMAGE_TYPE_1D),
	IMG_2D(VK_IMAGE_TYPE_2D),
	IMG_3D(VK_IMAGE_TYPE_3D),
	;
	
	public final int id;
	VKImageType(int id){ this.id = id; }
	@Override
	public int id(){ return id; }
	
	public static VKImageType from(int id){ return VUtils.fromID(VKImageType.class, id); }
}
