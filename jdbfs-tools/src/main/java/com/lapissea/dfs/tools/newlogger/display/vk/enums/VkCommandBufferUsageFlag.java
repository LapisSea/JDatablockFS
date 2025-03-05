package com.lapissea.dfs.tools.newlogger.display.vk.enums;

import com.lapissea.util.UtilL;

import java.util.EnumSet;

import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_USAGE_RENDER_PASS_CONTINUE_BIT;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_USAGE_SIMULTANEOUS_USE_BIT;

public enum VkCommandBufferUsageFlag{
	ONE_TIME_SUBMIT_BIT(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT),
	RENDER_PASS_CONTINUE_BIT(VK_COMMAND_BUFFER_USAGE_RENDER_PASS_CONTINUE_BIT),
	SIMULTANEOUS_USE_BIT(VK_COMMAND_BUFFER_USAGE_SIMULTANEOUS_USE_BIT),
	;
	
	public final int bit;
	VkCommandBufferUsageFlag(int bit){ this.bit = bit; }
	
	public static EnumSet<VkCommandBufferUsageFlag> from(int props){
		var flags = EnumSet.allOf(VkCommandBufferUsageFlag.class);
		flags.removeIf(flag -> !UtilL.checkFlag(props, flag.bit));
		return flags;
	}
	
}
