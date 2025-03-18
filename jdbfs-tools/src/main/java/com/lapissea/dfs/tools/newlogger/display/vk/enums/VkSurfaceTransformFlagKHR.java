package com.lapissea.dfs.tools.newlogger.display.vk.enums;

import com.lapissea.dfs.tools.newlogger.display.VUtils;
import com.lapissea.dfs.tools.newlogger.display.vk.Flags;

import static org.lwjgl.vulkan.KHRSurface.*;

public enum VkSurfaceTransformFlagKHR implements VUtils.FlagSetValue{
	IDENTITY(VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR),
	ROTATE_90(VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR),
	ROTATE_180(VK_SURFACE_TRANSFORM_ROTATE_180_BIT_KHR),
	ROTATE_270(VK_SURFACE_TRANSFORM_ROTATE_270_BIT_KHR),
	HORIZONTAL_MIRROR(VK_SURFACE_TRANSFORM_HORIZONTAL_MIRROR_BIT_KHR),
	HORIZONTAL_MIRROR_ROTATE_90(VK_SURFACE_TRANSFORM_HORIZONTAL_MIRROR_ROTATE_90_BIT_KHR),
	HORIZONTAL_MIRROR_ROTATE_180(VK_SURFACE_TRANSFORM_HORIZONTAL_MIRROR_ROTATE_180_BIT_KHR),
	HORIZONTAL_MIRROR_ROTATE_270(VK_SURFACE_TRANSFORM_HORIZONTAL_MIRROR_ROTATE_270_BIT_KHR),
	INHERIT(VK_SURFACE_TRANSFORM_INHERIT_BIT_KHR),
	;
	
	public final int bit;
	VkSurfaceTransformFlagKHR(int bit){ this.bit = bit; }
	
	@Override
	public int bit(){ return bit; }
	
	public static Flags<VkSurfaceTransformFlagKHR> from(int props){ return new Flags<>(VkSurfaceTransformFlagKHR.class, props); }
}
