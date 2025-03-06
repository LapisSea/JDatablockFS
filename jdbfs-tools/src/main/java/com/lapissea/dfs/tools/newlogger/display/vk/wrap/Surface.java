package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.VKCalls;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VKPresentMode;
import com.lapissea.dfs.utils.iterableplus.Iters;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;

import java.util.EnumSet;
import java.util.List;

public class Surface implements VulkanResource{
	
	public final  long       handle;
	private final VkInstance instance;
	
	public Surface(VkInstance instance, long handle){
		this.instance = instance;
		this.handle = handle;
	}
	
	public boolean supportsPresent(VkPhysicalDevice device, int familyIndex) throws VulkanCodeException{
		return VKCalls.vkGetPhysicalDeviceSurfaceSupportKHR(device, familyIndex, this);
	}
	
	public List<SurfaceFormat> getFormats(VkPhysicalDevice pDevice) throws VulkanCodeException{
		try(var mem = MemoryStack.stackPush()){
			
			var count = mem.mallocInt(1);
			VKCalls.vkGetPhysicalDeviceSurfaceFormatsKHR(pDevice, this, count, null);
			var formats = VkSurfaceFormatKHR.malloc(count.get(0), mem);
			VKCalls.vkGetPhysicalDeviceSurfaceFormatsKHR(pDevice, this, count, formats);
			
			return Iters.from(formats).toList(f -> new SurfaceFormat(f.format(), f.colorSpace()));
		}
	}
	
	public SurfaceCapabilities getCapabilities(PhysicalDevice physicalDevice) throws VulkanCodeException{
		return VKCalls.vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice.pDevice, handle);
	}
	
	public EnumSet<VKPresentMode> getPresentModes(VkPhysicalDevice physicalDevice) throws VulkanCodeException{
		var surface = this;
		
		try(var mem = MemoryStack.stackPush()){
			var pPresentModeCount = mem.mallocInt(1);
			VKCalls.vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, surface, pPresentModeCount, null);
			var pPresentModes = mem.mallocInt(pPresentModeCount.get(0));
			VKCalls.vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, surface, pPresentModeCount, pPresentModes);
			
			var res = EnumSet.noneOf(VKPresentMode.class);
			for(int i = 0; i<pPresentModes.capacity(); i++){
				res.add(VKPresentMode.from(pPresentModes.get(i)));
			}
			if(res.size() != pPresentModes.capacity()) throw new AssertionError();
			return res;
		}
	}
	
	@Override
	public void destroy(){
		KHRSurface.vkDestroySurfaceKHR(instance, handle, null);
	}
}
