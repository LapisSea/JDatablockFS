package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkSurfaceTransformFlagKHR;
import org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR;

public class SurfaceCapabilities{
	
	public final int      minImageCount;
	public final int      maxImageCount;
	public final Extent2D currentExtent;
	public final Extent2D minImageExtent;
	public final Extent2D maxImageExtent;
	
	public final VkSurfaceTransformFlagKHR currentTransform;
	
	private static VkSurfaceTransformFlagKHR getTransform(int flags){
		var e = VkSurfaceTransformFlagKHR.from(flags);
		if(e.size() != 1) throw new IllegalArgumentException("Unrecognised flags: " + flags);
		return e.getFirst();
	}
	public SurfaceCapabilities(VkSurfaceCapabilitiesKHR caps){
		this(
			caps.minImageCount(), caps.maxImageCount(),
			new Extent2D(caps.currentExtent()), new Extent2D(caps.minImageExtent()), new Extent2D(caps.maxImageExtent()),
			getTransform(caps.currentTransform())
		);
	}
	public SurfaceCapabilities(
		int minImageCount, int maxImageCount,
		Extent2D currentExtent, Extent2D minImageExtent, Extent2D maxImageExtent,
		VkSurfaceTransformFlagKHR currentTransform
	){
		this.minImageCount = minImageCount;
		this.maxImageCount = maxImageCount;
		this.currentExtent = currentExtent;
		this.minImageExtent = minImageExtent;
		this.maxImageExtent = maxImageExtent;
		this.currentTransform = currentTransform;
	}
}
