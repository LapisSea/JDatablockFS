package com.lapissea.dfs.inspect.display.vk.enums;

import com.lapissea.dfs.inspect.display.VUtils;
import com.lapissea.dfs.inspect.display.vk.Flags;

import static org.lwjgl.vulkan.EXTAttachmentFeedbackLoopLayout.VK_DEPENDENCY_FEEDBACK_LOOP_BIT_EXT;
import static org.lwjgl.vulkan.VK10.VK_DEPENDENCY_BY_REGION_BIT;
import static org.lwjgl.vulkan.VK11.VK_DEPENDENCY_DEVICE_GROUP_BIT;
import static org.lwjgl.vulkan.VK11.VK_DEPENDENCY_VIEW_LOCAL_BIT;

public enum VkDependencyFlag implements VUtils.FlagSetValue{
	
	BY_REGION_BIT(VK_DEPENDENCY_BY_REGION_BIT),
	DEVICE_GROUP_BIT(VK_DEPENDENCY_DEVICE_GROUP_BIT),
	VIEW_LOCAL_BIT(VK_DEPENDENCY_VIEW_LOCAL_BIT),
	FEEDBACK_LOOP_BIT_EXT(VK_DEPENDENCY_FEEDBACK_LOOP_BIT_EXT),
	;
	
	public final int bit;
	VkDependencyFlag(int bit){ this.bit = bit; }
	
	@Override
	public int bit(){ return bit; }
	
	public static Flags<VkDependencyFlag> from(int props){ return new Flags<>(VkDependencyFlag.class, props); }
}
