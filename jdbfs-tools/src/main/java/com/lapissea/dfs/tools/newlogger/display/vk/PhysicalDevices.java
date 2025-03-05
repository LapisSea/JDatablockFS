package com.lapissea.dfs.tools.newlogger.display.vk;

import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkQueueCapability;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.PhysicalDevice;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.QueueFamilyProps;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Surface;
import com.lapissea.dfs.utils.iterableplus.Iters;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;

import java.util.List;

import static com.lapissea.dfs.tools.newlogger.display.VUtils.check;
import static org.lwjgl.vulkan.VK10.vkEnumeratePhysicalDevices;

public class PhysicalDevices{
	
	private final List<PhysicalDevice> devices;
	
	public PhysicalDevices(VkInstance instance, Surface surface){
		List<VkPhysicalDevice> handles;
		try(var mem = MemoryStack.stackPush()){
			var count = mem.mallocInt(1);
			check(vkEnumeratePhysicalDevices(instance, count, null), "enumeratePhysicalDevices");
			
			var ptrs = mem.mallocPointer(count.get(0));
			check(vkEnumeratePhysicalDevices(instance, count, ptrs), "enumeratePhysicalDevices");
			
			handles = Iters.rangeMap(0, ptrs.capacity(), i -> new VkPhysicalDevice(ptrs.get(i), instance)).toList();
		}
		devices = Iters.from(handles).toList(h -> new PhysicalDevice(h, surface));
	}
	
	public PhysicalDevice selectDevice(VkQueueCapability requiredCapability, boolean supportsPresent){
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
