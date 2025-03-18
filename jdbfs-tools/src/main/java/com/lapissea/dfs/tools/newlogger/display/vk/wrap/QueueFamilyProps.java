package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkQueueFlag;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public class QueueFamilyProps{
	
	public final int              index;
	public final boolean          supportsPresent;
	public final int              queueCount;
	public final Set<VkQueueFlag> capabilities;
	
	public QueueFamilyProps(VkPhysicalDevice device, Surface surface, VkQueueFamilyProperties properties, int index) throws VulkanCodeException{
		this.index = index;
		supportsPresent = surface.supportsPresent(device, index);
		var caps = EnumSet.noneOf(VkQueueFlag.class);
		caps.addAll(VkQueueFlag.from(properties.queueFlags()));
		queueCount = properties.queueCount();
		capabilities = Collections.unmodifiableSet(caps);
	}
	
}
