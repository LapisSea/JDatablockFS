package com.lapissea.dfs.tools.newlogger.display.vk.enums;

import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.UtilL;

import java.util.List;

import static org.lwjgl.vulkan.VK10.VK_FRONT_FACE_CLOCKWISE;
import static org.lwjgl.vulkan.VK10.VK_FRONT_FACE_COUNTER_CLOCKWISE;

public enum VkFrontFace{
	COUNTER_CLOCKWISE(VK_FRONT_FACE_COUNTER_CLOCKWISE),
	CLOCKWISE(VK_FRONT_FACE_CLOCKWISE),
	;
	
	public final int bit;
	VkFrontFace(int bit){ this.bit = bit; }
	
	public static List<VkFrontFace> from(int props){
		return Iters.from(VkFrontFace.class).filter(cap -> UtilL.checkFlag(props, cap.bit)).toList();
	}
}
