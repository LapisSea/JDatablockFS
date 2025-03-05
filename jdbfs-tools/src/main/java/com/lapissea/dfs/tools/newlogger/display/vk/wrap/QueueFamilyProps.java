package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkQueueCapability;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public class QueueFamilyProps{
	
	public final int                    index;
	public final boolean                supportsPresent;
	public final int                    queueCount;
	public final Set<VkQueueCapability> capabilities;
	
	public QueueFamilyProps(VkPhysicalDevice device, Surface surface, VkQueueFamilyProperties properties, int index){
		this.index = index;
		supportsPresent = surface.supportsPresent(device, index);
		var caps = EnumSet.noneOf(VkQueueCapability.class);
		caps.addAll(VkQueueCapability.from(properties.queueFlags()));
		queueCount = properties.queueCount();
		capabilities = Collections.unmodifiableSet(caps);
	}
	
}
