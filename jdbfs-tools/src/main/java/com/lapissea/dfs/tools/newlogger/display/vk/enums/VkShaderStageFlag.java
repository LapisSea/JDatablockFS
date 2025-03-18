package com.lapissea.dfs.tools.newlogger.display.vk.enums;

import com.lapissea.dfs.tools.newlogger.display.VUtils;
import com.lapissea.dfs.tools.newlogger.display.vk.Flags;

import static org.lwjgl.vulkan.VK10.*;

public enum VkShaderStageFlag implements VUtils.FlagSetValue{
	VERTEX(VK_SHADER_STAGE_VERTEX_BIT),
	TESSELLATION_CONTROL(VK_SHADER_STAGE_TESSELLATION_CONTROL_BIT),
	TESSELLATION_EVALUATION(VK_SHADER_STAGE_TESSELLATION_EVALUATION_BIT),
	GEOMETRY(VK_SHADER_STAGE_GEOMETRY_BIT),
	FRAGMENT(VK_SHADER_STAGE_FRAGMENT_BIT),
	COMPUTE(VK_SHADER_STAGE_COMPUTE_BIT),
	;
	
	public final int bit;
	VkShaderStageFlag(int bit){ this.bit = bit; }
	
	@Override
	public int bit(){ return bit; }
	
	public static Flags<VkShaderStageFlag> from(int props){ return new Flags<>(VkShaderStageFlag.class, props); }
}
