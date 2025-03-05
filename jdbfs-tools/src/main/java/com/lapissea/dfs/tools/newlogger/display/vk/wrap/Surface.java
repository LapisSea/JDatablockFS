package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VKPresentMode;
import com.lapissea.dfs.utils.iterableplus.Iters;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;

import java.util.EnumSet;
import java.util.List;

import static com.lapissea.dfs.tools.newlogger.display.VUtils.check;
import static org.lwjgl.glfw.GLFWVulkan.glfwCreateWindowSurface;

public class Surface implements VulkanResource{
	
	public static Surface create(VkInstance instance, long windowHandle){
		var surfaceRef = new long[1];
		check(glfwCreateWindowSurface(instance, windowHandle, null, surfaceRef), "createWindowSurface");
		return new Surface(instance, surfaceRef[0]);
	}
	
	public final  long       handle;
	private final VkInstance instance;
	
	public Surface(VkInstance instance, long handle){
		this.instance = instance;
		this.handle = handle;
	}
	
	public boolean supportsPresent(VkPhysicalDevice device, int familyIndex){
		var res = new int[1];
		check(KHRSurface.vkGetPhysicalDeviceSurfaceSupportKHR(device, familyIndex, handle, res), "getPhysicalDeviceSurfaceSupport");
		return res[0] == VK10.VK_TRUE;
	}
	
	public List<SurfaceFormat> getFormats(VkPhysicalDevice pDevice){
		try(var mem = MemoryStack.stackPush()){
			
			var count = new int[1];
			check(KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(pDevice, handle, count, null), "getPhysicalDeviceSurfaceFormats");
			var formats = VkSurfaceFormatKHR.malloc(count[0], mem);
			check(KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(pDevice, handle, count, formats), "getPhysicalDeviceSurfaceFormats");
			
			return Iters.from(formats).toList(f -> new SurfaceFormat(f.format(), f.colorSpace()));
		}
	}
	
	public SurfaceCapabilities getCapabilities(VkPhysicalDevice pDevice){
		return SurfaceCapabilities.from(pDevice, handle);
	}
	
	public EnumSet<VKPresentMode> getPresentModes(VkPhysicalDevice pDevice){
		try(var mem = MemoryStack.stackPush()){
			var count = mem.mallocInt(1);
			check(KHRSurface.vkGetPhysicalDeviceSurfacePresentModesKHR(pDevice, handle, count, null), "getPhysicalDeviceSurfacePresentModes");
			var modes = mem.mallocInt(count.get(0));
			check(KHRSurface.vkGetPhysicalDeviceSurfacePresentModesKHR(pDevice, handle, count, modes), "getPhysicalDeviceSurfacePresentModes");
			
			var res = EnumSet.noneOf(VKPresentMode.class);
			for(int i = 0; i<modes.capacity(); i++){
				res.add(VKPresentMode.from(modes.get(i)));
			}
			if(res.size() != modes.capacity()) throw new AssertionError();
			return res;
		}
	}
	
	@Override
	public void destroy(){
		KHRSurface.vkDestroySurfaceKHR(instance, handle, null);
	}
}
