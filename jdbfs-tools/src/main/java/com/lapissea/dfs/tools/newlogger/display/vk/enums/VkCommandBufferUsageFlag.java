package com.lapissea.dfs.tools.newlogger.display.vk.enums;

import com.lapissea.dfs.tools.newlogger.display.VUtils;
import com.lapissea.dfs.tools.newlogger.display.vk.Flags;

import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_USAGE_RENDER_PASS_CONTINUE_BIT;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_USAGE_SIMULTANEOUS_USE_BIT;

public enum VkCommandBufferUsageFlag implements VUtils.FlagSetValue{
	ONE_TIME_SUBMIT_BIT(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT),
	RENDER_PASS_CONTINUE_BIT(VK_COMMAND_BUFFER_USAGE_RENDER_PASS_CONTINUE_BIT),
	SIMULTANEOUS_USE_BIT(VK_COMMAND_BUFFER_USAGE_SIMULTANEOUS_USE_BIT),
	;
	
	public final int bit;
	VkCommandBufferUsageFlag(int bit){ this.bit = bit; }
	
	@Override
	public int bit(){ return bit; }
	
	public static Flags<VkCommandBufferUsageFlag> from(int props){ return new Flags<>(VkCommandBufferUsageFlag.class, props); }
}
