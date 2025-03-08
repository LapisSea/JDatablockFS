package com.lapissea.dfs.tools.newlogger.display.vk.enums;

import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.UtilL;

import java.util.List;

import static org.lwjgl.vulkan.VK10.*;

public enum VkShaderStageFlag{
	VERTEX(VK_SHADER_STAGE_VERTEX_BIT),
	TESSELLATION_CONTROL(VK_SHADER_STAGE_TESSELLATION_CONTROL_BIT),
	TESSELLATION_EVALUATION(VK_SHADER_STAGE_TESSELLATION_EVALUATION_BIT),
	GEOMETRY(VK_SHADER_STAGE_GEOMETRY_BIT),
	FRAGMENT(VK_SHADER_STAGE_FRAGMENT_BIT),
	COMPUTE(VK_SHADER_STAGE_COMPUTE_BIT),
	;
	
	public final int bit;
	VkShaderStageFlag(int bit){ this.bit = bit; }
	
	public static List<VkShaderStageFlag> from(int props){
		return Iters.from(VkShaderStageFlag.class).filter(cap -> UtilL.checkFlag(props, cap.bit)).toList();
	}
}
