package com.lapissea.dfs.tools.newlogger.display.vk.enums;

import com.lapissea.dfs.tools.newlogger.display.VUtils;
import com.lapissea.dfs.tools.newlogger.display.vk.Flags;

import static org.lwjgl.vulkan.VK10.VK_FRONT_FACE_CLOCKWISE;
import static org.lwjgl.vulkan.VK10.VK_FRONT_FACE_COUNTER_CLOCKWISE;

public enum VkFrontFace implements VUtils.FlagSetValue{
	COUNTER_CLOCKWISE(VK_FRONT_FACE_COUNTER_CLOCKWISE),
	CLOCKWISE(VK_FRONT_FACE_CLOCKWISE),
	;
	
	public final int bit;
	VkFrontFace(int bit){ this.bit = bit; }
	
	@Override
	public int bit(){ return bit; }
	
	public static Flags<VkFrontFace> from(int props){ return new Flags<>(VkFrontFace.class, props); }
}
