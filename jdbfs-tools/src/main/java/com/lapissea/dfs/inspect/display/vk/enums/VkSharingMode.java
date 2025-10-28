package com.lapissea.dfs.inspect.display.vk.enums;

import com.lapissea.dfs.inspect.display.VUtils;

import static org.lwjgl.vulkan.VK10.VK_SHARING_MODE_CONCURRENT;
import static org.lwjgl.vulkan.VK10.VK_SHARING_MODE_EXCLUSIVE;

public enum VkSharingMode implements VUtils.IDValue{
	EXCLUSIVE(VK_SHARING_MODE_EXCLUSIVE),
	CONCURRENT(VK_SHARING_MODE_CONCURRENT),
	;
	
	public final int id;
	VkSharingMode(int id){ this.id = id; }
	@Override
	public int id(){ return id; }
	
	public static VkSharingMode from(int id){ return VUtils.fromID(VkSharingMode.class, id); }
}
