package com.lapissea.dfs.inspect.display.vk.enums;

import com.lapissea.dfs.inspect.display.VUtils;

import static org.lwjgl.vulkan.VK10.*;

public enum VkPhysicalDeviceType implements VUtils.IDValue{
	
	OTHER(VK_PHYSICAL_DEVICE_TYPE_OTHER),
	INTEGRATED_GPU(VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU),
	DISCRETE_GPU(VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU),
	VIRTUAL_GPU(VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU),
	CPU(VK_PHYSICAL_DEVICE_TYPE_CPU),
	;
	
	public final int id;
	VkPhysicalDeviceType(int id){ this.id = id; }
	@Override
	public int id(){ return id; }
	
	public static VkPhysicalDeviceType from(int id){ return VUtils.fromID(VkPhysicalDeviceType.class, id); }
	
}
