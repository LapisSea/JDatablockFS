package com.lapissea.dfs.tools.newlogger.display.vk.enums;

import com.lapissea.dfs.utils.iterableplus.Iters;

import java.util.Map;

import static org.lwjgl.vulkan.KHRSurface.VK_PRESENT_MODE_FIFO_KHR;
import static org.lwjgl.vulkan.KHRSurface.VK_PRESENT_MODE_FIFO_RELAXED_KHR;
import static org.lwjgl.vulkan.KHRSurface.VK_PRESENT_MODE_IMMEDIATE_KHR;
import static org.lwjgl.vulkan.KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR;

public enum VKPresentMode{
	
	IMMEDIATE(VK_PRESENT_MODE_IMMEDIATE_KHR),
	MAILBOX(VK_PRESENT_MODE_MAILBOX_KHR),
	FIFO(VK_PRESENT_MODE_FIFO_KHR),
	FIFO_RELAXED(VK_PRESENT_MODE_FIFO_RELAXED_KHR);
	
	public final int id;
	VKPresentMode(int id){ this.id = id; }
	
	private static final Map<Integer, VKPresentMode> BY_ID = Iters.from(VKPresentMode.class).toMap(e -> e.id, e -> e);
	
	public static VKPresentMode from(int id){
		var value = BY_ID.get(id);
		if(value == null){
			throw new IllegalArgumentException("Unknown id: " + id);
		}
		return value;
	}
}
