package com.lapissea.dfs.inspect.display.vk.enums;

import com.lapissea.dfs.inspect.display.VUtils;
import com.lapissea.dfs.inspect.display.vk.Flags;

import static org.lwjgl.vulkan.VK10.*;

public enum VkSampleCountFlag implements VUtils.FlagSetValue{
	
	N1(VK_SAMPLE_COUNT_1_BIT),
	N2(VK_SAMPLE_COUNT_2_BIT),
	N4(VK_SAMPLE_COUNT_4_BIT),
	N8(VK_SAMPLE_COUNT_8_BIT),
	N16(VK_SAMPLE_COUNT_16_BIT),
	N32(VK_SAMPLE_COUNT_32_BIT),
	N64(VK_SAMPLE_COUNT_64_BIT),
	;
	
	public final int bit;
	VkSampleCountFlag(int bit){ this.bit = bit; }
	
	@Override
	public int bit(){ return bit; }
	
	public static Flags<VkSampleCountFlag> from(int props){ return new Flags<>(VkSampleCountFlag.class, props); }
}
