package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.vk.Flags;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkQueueFlag;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

public class QueueFamilyProps{
	
	public final int                index;
	public final int                queueCount;
	public final Flags<VkQueueFlag> capabilities;
	public final Extent3D           minImageTransferGranularity;
	
	public QueueFamilyProps(int index, VkQueueFamilyProperties properties){
		this.index = index;
		queueCount = properties.queueCount();
		capabilities = VkQueueFlag.from(properties.queueFlags());
		minImageTransferGranularity = new Extent3D(properties.minImageTransferGranularity());
		
	}
	
	@Override
	public String toString(){
		return capabilities + "x" + queueCount + "@" + index;
	}
}
