package com.lapissea.dfs.tools.newlogger.display.vk.enums;

import com.lapissea.dfs.utils.iterableplus.Iters;

import java.util.Map;

import static org.lwjgl.vulkan.VK10.VK_IMAGE_TYPE_1D;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_TYPE_2D;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_TYPE_3D;

public enum VKImageType{
	IMG_1D(VK_IMAGE_TYPE_1D),
	IMG_2D(VK_IMAGE_TYPE_2D),
	IMG_3D(VK_IMAGE_TYPE_3D),
	;
	
	public final int id;
	VKImageType(int id){ this.id = id; }
	
	private static final Map<Integer, VKImageType> BY_ID = Iters.from(VKImageType.class).toMap(e -> e.id, e -> e);
	
	public static VKImageType from(int id){
		var value = BY_ID.get(id);
		if(value == null){
			throw new IllegalArgumentException("Unknown id: " + id);
		}
		return value;
	}
}
