package com.lapissea.dfs.tools.newlogger.display.vk.enums;

import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.UtilL;

import java.util.List;

import static org.lwjgl.vulkan.VK10.*;

public enum VkSampleCountFlag{
	
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
	
	public static List<VkSampleCountFlag> from(int props){
		return Iters.from(VkSampleCountFlag.class).filter(cap -> UtilL.checkFlag(props, cap.bit)).toList();
	}
}
