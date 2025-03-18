package com.lapissea.dfs.tools.newlogger.display.vk;

import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkQueueFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.PhysicalDevice;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.QueueFamilyProps;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Surface;
import com.lapissea.dfs.utils.iterableplus.Iters;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;

import java.util.ArrayList;
import java.util.List;

public class PhysicalDevices{
	
	private final List<PhysicalDevice> devices;
	
	public PhysicalDevices(VkInstance instance, Surface surface) throws VulkanCodeException{
		List<VkPhysicalDevice> handles;
		try(var mem = MemoryStack.stackPush()){
			var count = mem.mallocInt(1);
			VKCalls.vkEnumeratePhysicalDevices(instance, count, null);
			
			var ptrs = mem.mallocPointer(count.get(0));
			VKCalls.vkEnumeratePhysicalDevices(instance, count, ptrs);
			
			handles = Iters.rangeMap(0, ptrs.capacity(), i -> new VkPhysicalDevice(ptrs.get(i), instance)).toList();
		}
		var devices = new ArrayList<PhysicalDevice>(handles.size());
		for(var handle : handles){
			devices.add(new PhysicalDevice(handle, surface));
		}
		this.devices = List.copyOf(devices);
	}
	
	public PhysicalDevice selectDevice(VkQueueFlag requiredCapability, boolean supportsPresent){
		for(PhysicalDevice device : devices){
			for(QueueFamilyProps family : device.families){
				if(supportsPresent && !family.supportsPresent){
					continue;
				}
				if(!family.capabilities.contains(requiredCapability)){
					continue;
				}
				return device;
			}
		}
		throw new IllegalStateException("Could not find compatible physical device: " + devices);
	}
	
}
