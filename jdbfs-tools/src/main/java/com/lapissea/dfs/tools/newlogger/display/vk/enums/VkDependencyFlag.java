package com.lapissea.dfs.tools.newlogger.display.vk.enums;

import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.UtilL;

import java.util.List;

import static org.lwjgl.vulkan.EXTAttachmentFeedbackLoopLayout.VK_DEPENDENCY_FEEDBACK_LOOP_BIT_EXT;
import static org.lwjgl.vulkan.VK10.VK_DEPENDENCY_BY_REGION_BIT;
import static org.lwjgl.vulkan.VK11.VK_DEPENDENCY_DEVICE_GROUP_BIT;
import static org.lwjgl.vulkan.VK11.VK_DEPENDENCY_VIEW_LOCAL_BIT;

public enum VkDependencyFlag{
	
	BY_REGION_BIT(VK_DEPENDENCY_BY_REGION_BIT),
	DEVICE_GROUP_BIT(VK_DEPENDENCY_DEVICE_GROUP_BIT),
	VIEW_LOCAL_BIT(VK_DEPENDENCY_VIEW_LOCAL_BIT),
	FEEDBACK_LOOP_BIT_EXT(VK_DEPENDENCY_FEEDBACK_LOOP_BIT_EXT),
	;
	
	public final int bit;
	VkDependencyFlag(int bit){ this.bit = bit; }
	
	public static List<VkDependencyFlag> from(int props){
		return Iters.from(VkDependencyFlag.class).filter(cap -> UtilL.checkFlag(props, cap.bit)).toList();
	}
}
