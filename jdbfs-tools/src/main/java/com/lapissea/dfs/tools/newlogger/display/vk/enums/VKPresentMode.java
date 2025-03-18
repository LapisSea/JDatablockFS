package com.lapissea.dfs.tools.newlogger.display.vk.enums;

import com.lapissea.dfs.tools.newlogger.display.VUtils;

import static org.lwjgl.vulkan.KHRSurface.VK_PRESENT_MODE_FIFO_KHR;
import static org.lwjgl.vulkan.KHRSurface.VK_PRESENT_MODE_FIFO_RELAXED_KHR;
import static org.lwjgl.vulkan.KHRSurface.VK_PRESENT_MODE_IMMEDIATE_KHR;
import static org.lwjgl.vulkan.KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR;

public enum VKPresentMode implements VUtils.IDValue{
	
	IMMEDIATE(VK_PRESENT_MODE_IMMEDIATE_KHR),
	MAILBOX(VK_PRESENT_MODE_MAILBOX_KHR),
	FIFO(VK_PRESENT_MODE_FIFO_KHR),
	FIFO_RELAXED(VK_PRESENT_MODE_FIFO_RELAXED_KHR);
	
	public final int id;
	VKPresentMode(int id){ this.id = id; }
	@Override
	public int id(){ return id; }
	
	public static VKPresentMode from(int id){ return VUtils.fromID(VKPresentMode.class, id); }
}
