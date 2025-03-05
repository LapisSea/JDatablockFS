package com.lapissea.dfs.tools.newlogger.display.vk.enums;

import com.lapissea.dfs.utils.iterableplus.Iters;

import java.util.Map;

import static org.lwjgl.vulkan.VK10.*;

public enum VkPhysicalDeviceType{
	
	OTHER(VK_PHYSICAL_DEVICE_TYPE_OTHER),
	INTEGRATED_GPU(VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU),
	DISCRETE_GPU(VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU),
	VIRTUAL_GPU(VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU),
	CPU(VK_PHYSICAL_DEVICE_TYPE_CPU),
	;
	
	public final int id;
	VkPhysicalDeviceType(int id){ this.id = id; }
	
	private static final Map<Integer, VkPhysicalDeviceType> BY_ID = Iters.from(VkPhysicalDeviceType.class).toMap(e -> e.id, e -> e);
	
	public static VkPhysicalDeviceType from(int id){
		var value = BY_ID.get(id);
		if(value == null){
			throw new IllegalArgumentException("Unknown id: " + id);
		}
		return value;
	}
	
}
