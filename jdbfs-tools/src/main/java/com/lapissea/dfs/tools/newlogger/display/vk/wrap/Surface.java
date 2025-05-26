package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.VKCalls;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VKPresentMode;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkColorSpaceKHR;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkFormat;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.dfs.utils.iterableplus.Match;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.SequencedSet;

public class Surface implements VulkanResource{
	
	public final  long       handle;
	private final VkInstance instance;
	
	public Surface(VkInstance instance, long handle){
		this.instance = instance;
		this.handle = handle;
	}
	
	public boolean supportsPresent(VkPhysicalDevice device, QueueFamilyProps family) throws VulkanCodeException{
		return VKCalls.vkGetPhysicalDeviceSurfaceSupportKHR(device, family.index, this);
	}
	
	private static final FormatColor DUMMY_FAIL_FORMAT = new FormatColor(VkFormat.UNDEFINED, VkColorSpaceKHR.SRGB_NONLINEAR_KHR);
	private              FormatColor lastFormat;
	public FormatColor chooseSwapchainFormat(PhysicalDevice physicalDevice, Iterable<FormatColor> preferred) throws VulkanCodeException{
		var formats = getFormats(physicalDevice);
		return switch(Iters.from(preferred).firstMatchingM(formats::contains)){
			case Match.Some(var f) -> {
				if(!Objects.equals(lastFormat, f)){
					lastFormat = f;
					Log.info("Found format: {}#green", f);
				}
				yield f;
			}
			case Match.None() -> {
				var f = Iters.from(formats).firstMatching(fo -> Iters.from(preferred).map(fp -> fp.format).anyIs(fo.format));
				if(f.isEmpty()){
					f = Iters.from(formats).firstMatching(fo -> Iters.from(preferred).map(fp -> fp.colorSpace).anyIs(fo.colorSpace));
				}
				if(f.isEmpty()){
					f = Optional.of(formats.getFirst());
				}
				if(lastFormat != DUMMY_FAIL_FORMAT){
					lastFormat = DUMMY_FAIL_FORMAT;
					Log.warn("Found no preferred formats! Using: {}#yellow", f.map(Object::toString).orElse("UNKNOWN"));
				}
				yield f.get();
			}
		};
	}
	
	public SequencedSet<FormatColor> getFormats(PhysicalDevice pDevice) throws VulkanCodeException{
		return getFormats(pDevice.pDevice);
	}
	public SequencedSet<FormatColor> getFormats(VkPhysicalDevice pDevice) throws VulkanCodeException{
		try(var mem = MemoryStack.stackPush()){
			
			var count = mem.mallocInt(1);
			VKCalls.vkGetPhysicalDeviceSurfaceFormatsKHR(pDevice, this, count, null);
			var formats = VkSurfaceFormatKHR.malloc(count.get(0), mem);
			VKCalls.vkGetPhysicalDeviceSurfaceFormatsKHR(pDevice, this, count, formats);
			
			return Iters.from(formats).toSequencedSet(f -> new FormatColor(f.format(), f.colorSpace()));
		}
	}
	
	public SurfaceCapabilities getCapabilities(PhysicalDevice physicalDevice) throws VulkanCodeException{
		return VKCalls.vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice.pDevice, handle);
	}
	
	public EnumSet<VKPresentMode> getPresentModes(PhysicalDevice physicalDevice) throws VulkanCodeException{
		return getPresentModes(physicalDevice.pDevice);
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
