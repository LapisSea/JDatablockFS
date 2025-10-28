package com.lapissea.dfs.inspect.display.vk.enums;

import com.lapissea.dfs.inspect.display.VUtils;
import com.lapissea.dfs.inspect.display.vk.Flags;

import static org.lwjgl.vulkan.VK10.VK_CULL_MODE_BACK_BIT;
import static org.lwjgl.vulkan.VK10.VK_CULL_MODE_FRONT_AND_BACK;
import static org.lwjgl.vulkan.VK10.VK_CULL_MODE_FRONT_BIT;
import static org.lwjgl.vulkan.VK10.VK_CULL_MODE_NONE;

public enum VkCullModeFlag implements VUtils.FlagSetValue{
	NONE(VK_CULL_MODE_NONE),
	FRONT(VK_CULL_MODE_FRONT_BIT),
	BACK(VK_CULL_MODE_BACK_BIT),
	FRONT_AND_BACK(VK_CULL_MODE_FRONT_AND_BACK),
	;
	
	public final int bit;
	VkCullModeFlag(int bit){ this.bit = bit; }
	
	@Override
	public int bit(){ return bit; }
	
	public static Flags<VkCullModeFlag> from(int props){ return new Flags<>(VkCullModeFlag.class, props); }
}
