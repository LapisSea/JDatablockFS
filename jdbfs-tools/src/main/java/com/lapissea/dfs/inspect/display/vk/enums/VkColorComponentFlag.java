package com.lapissea.dfs.inspect.display.vk.enums;

import com.lapissea.dfs.inspect.display.VUtils;
import com.lapissea.dfs.inspect.display.vk.Flags;

import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_A_BIT;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_B_BIT;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_G_BIT;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_R_BIT;

public enum VkColorComponentFlag implements VUtils.FlagSetValue{
	R_BIT(VK_COLOR_COMPONENT_R_BIT),
	G_BIT(VK_COLOR_COMPONENT_G_BIT),
	B_BIT(VK_COLOR_COMPONENT_B_BIT),
	A_BIT(VK_COLOR_COMPONENT_A_BIT),
	;
	public final int bit;
	VkColorComponentFlag(int bit){
		this.bit = bit;
	}
	
	@Override
	public int bit(){ return bit; }
	
	public static Flags<VkColorComponentFlag> from(int props){ return new Flags<>(VkColorComponentFlag.class, props); }
}
