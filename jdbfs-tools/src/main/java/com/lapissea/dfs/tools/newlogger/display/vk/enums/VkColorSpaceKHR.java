package com.lapissea.dfs.tools.newlogger.display.vk.enums;

import com.lapissea.dfs.utils.iterableplus.Iters;

import java.util.Map;

import static org.lwjgl.vulkan.AMDDisplayNativeHdr.VK_COLOR_SPACE_DISPLAY_NATIVE_AMD;
import static org.lwjgl.vulkan.EXTSwapchainColorspace.*;
import static org.lwjgl.vulkan.KHRSurface.VK_COLORSPACE_SRGB_NONLINEAR_KHR;
import static org.lwjgl.vulkan.KHRSurface.VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;

public enum VkColorSpaceKHR{
	SRGB_NONLINEAR_KHR(VK_COLOR_SPACE_SRGB_NONLINEAR_KHR),
	DISPLAY_P3_NONLINEAR_EXT(VK_COLOR_SPACE_DISPLAY_P3_NONLINEAR_EXT),
	EXTENDED_SRGB_LINEAR_EXT(VK_COLOR_SPACE_EXTENDED_SRGB_LINEAR_EXT),
	DISPLAY_P3_LINEAR_EXT(VK_COLOR_SPACE_DISPLAY_P3_LINEAR_EXT),
	DCI_P3_NONLINEAR_EXT(VK_COLOR_SPACE_DCI_P3_NONLINEAR_EXT),
	BT709_LINEAR_EXT(VK_COLOR_SPACE_BT709_LINEAR_EXT),
	BT709_NONLINEAR_EXT(VK_COLOR_SPACE_BT709_NONLINEAR_EXT),
	BT2020_LINEAR_EXT(VK_COLOR_SPACE_BT2020_LINEAR_EXT),
	HDR10_ST2084_EXT(VK_COLOR_SPACE_HDR10_ST2084_EXT),
	DOLBYVISION_EXT(VK_COLOR_SPACE_DOLBYVISION_EXT),
	HDR10_HLG_EXT(VK_COLOR_SPACE_HDR10_HLG_EXT),
	ADOBERGB_LINEAR_EXT(VK_COLOR_SPACE_ADOBERGB_LINEAR_EXT),
	ADOBERGB_NONLINEAR_EXT(VK_COLOR_SPACE_ADOBERGB_NONLINEAR_EXT),
	PASS_THROUGH_EXT(VK_COLOR_SPACE_PASS_THROUGH_EXT),
	EXTENDED_SRGB_NONLINEAR_EXT(VK_COLOR_SPACE_EXTENDED_SRGB_NONLINEAR_EXT),
	DISPLAY_NATIVE_AMD(VK_COLOR_SPACE_DISPLAY_NATIVE_AMD),
	COLORSPACE_SRGB_NONLINEAR_KHR(VK_COLORSPACE_SRGB_NONLINEAR_KHR),
	DCI_P3_LINEAR_EXT(VK_COLOR_SPACE_DCI_P3_LINEAR_EXT);
	
	public final int id;
	
	VkColorSpaceKHR(int id){ this.id = id; }
	
	private static final Map<Integer, VkColorSpaceKHR> BY_ID =
		Iters.from(VkColorSpaceKHR.class).map(e -> e.id).distinct()
		     .toMap(
			     id -> id,
			     id -> Iters.from(VkColorSpaceKHR.class).firstMatching(e -> e.id == id).orElseThrow()
		     );
	
	public static VkColorSpaceKHR from(int id){
		var value = BY_ID.get(id);
		if(value == null){
			throw new IllegalArgumentException("Unknown id: " + id);
		}
		return value;
	}
}
