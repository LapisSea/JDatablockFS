package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.Flags;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkQueueFlag;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

public class QueueFamilyProps{
	
	public final int                index;
	public final boolean            supportsPresent;
	public final int                queueCount;
	public final Flags<VkQueueFlag> capabilities;
	
	public QueueFamilyProps(VkPhysicalDevice device, Surface surface, VkQueueFamilyProperties properties, int index) throws VulkanCodeException{
		this.index = index;
		supportsPresent = surface.supportsPresent(device, index);
		queueCount = properties.queueCount();
		capabilities = VkQueueFlag.from(properties.queueFlags());
	}
	
}
