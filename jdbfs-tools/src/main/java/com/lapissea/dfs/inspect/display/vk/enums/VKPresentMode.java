package com.lapissea.dfs.inspect.display.vk.enums;

import com.lapissea.dfs.inspect.display.VUtils;

import static org.lwjgl.vulkan.KHRSharedPresentableImage.VK_PRESENT_MODE_SHARED_CONTINUOUS_REFRESH_KHR;
import static org.lwjgl.vulkan.KHRSharedPresentableImage.VK_PRESENT_MODE_SHARED_DEMAND_REFRESH_KHR;
import static org.lwjgl.vulkan.KHRSurface.VK_PRESENT_MODE_FIFO_KHR;
import static org.lwjgl.vulkan.KHRSurface.VK_PRESENT_MODE_FIFO_RELAXED_KHR;
import static org.lwjgl.vulkan.KHRSurface.VK_PRESENT_MODE_IMMEDIATE_KHR;
import static org.lwjgl.vulkan.KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR;

public enum VKPresentMode implements VUtils.IDValue{
	
	IMMEDIATE(VK_PRESENT_MODE_IMMEDIATE_KHR),
	MAILBOX(VK_PRESENT_MODE_MAILBOX_KHR),
	FIFO(VK_PRESENT_MODE_FIFO_KHR),
	FIFO_RELAXED(VK_PRESENT_MODE_FIFO_RELAXED_KHR),
	SHARED_DEMAND_REFRESH_KHR(VK_PRESENT_MODE_SHARED_DEMAND_REFRESH_KHR),
	SHARED_CONTINUOUS_REFRESH_KHR(VK_PRESENT_MODE_SHARED_CONTINUOUS_REFRESH_KHR),
	;
	public final int id;
	VKPresentMode(int id){ this.id = id; }
	@Override
	public int id(){ return id; }
	
	public static VKPresentMode from(int id){ return VUtils.fromID(VKPresentMode.class, id); }
}
