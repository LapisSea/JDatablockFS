package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.VUtils;
import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.VKCalls;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VKPresentMode;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkPhysicalDeviceType;
import com.lapissea.dfs.utils.iterableplus.Iters;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRShaderDrawParameters;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class PhysicalDevice{
	
	public final VkPhysicalDevice       pDevice;
	public final String                 name;
	public final VkPhysicalDeviceType   type;
	public final List<QueueFamilyProps> families;
	public final List<SurfaceFormat>    formats;
	public final Set<VKPresentMode>     presentModes;
	public final List<MemoryType>       memoryTypes;
	public final List<MemoryHeap>       memoryHeaps;
	
	public PhysicalDevice(VkPhysicalDevice pDevice, Surface surface) throws VulkanCodeException{
		this.pDevice = pDevice;
		try(var stack = MemoryStack.stackPush()){
			var properties = VkPhysicalDeviceProperties.malloc(stack);
			VK10.vkGetPhysicalDeviceProperties(pDevice, properties);
			
			name = properties.deviceNameString();
			type = VkPhysicalDeviceType.from(properties.deviceType());
			
			families = getQueueFamilies(pDevice, surface, stack);
		}
		formats = surface.getFormats(pDevice);
		presentModes = Collections.unmodifiableSet(surface.getPresentModes(pDevice));
		
		try(var stack = MemoryStack.stackPush()){
			var mem = VkPhysicalDeviceMemoryProperties.malloc(stack);
			VK10.vkGetPhysicalDeviceMemoryProperties(pDevice, mem);
			
			memoryTypes = Iters.from(mem.memoryTypes()).map(e -> new MemoryType(e.heapIndex(), e.propertyFlags())).toList();
			memoryHeaps = Iters.from(mem.memoryHeaps()).map(e -> new MemoryHeap(e.flags(), e.size())).toList();
		}

//		LogUtil.println(TextUtil.toTable(name, families));
//		LogUtil.println(TextUtil.toTable(name, formats));
//		LogUtil.println(TextUtil.toNamedPrettyJson(surfaceCapabilities));
//		LogUtil.println("presentModes", presentModes);
//		LogUtil.println(TextUtil.toTable(memoryTypes));
//		LogUtil.println(TextUtil.toTable(memoryHeaps));
	}
	
	private List<QueueFamilyProps> getQueueFamilies(VkPhysicalDevice pDevice, Surface surface, MemoryStack stack) throws VulkanCodeException{
		var ib = stack.mallocInt(1);
		VK10.vkGetPhysicalDeviceQueueFamilyProperties(pDevice, ib, null);
		var familyProps = VkQueueFamilyProperties.malloc(ib.get(0), stack);
		VK10.vkGetPhysicalDeviceQueueFamilyProperties(pDevice, ib, familyProps);
		
		var families = new ArrayList<QueueFamilyProps>();
		for(VkQueueFamilyProperties familyProp : familyProps){
			families.add(new QueueFamilyProps(pDevice, surface, familyProp, families.size()));
		}
		return List.copyOf(families);
	}
	
	
	public Device createDevice(QueueFamilyProps family) throws VulkanCodeException{
		try(var stack = MemoryStack.stackPush()){
			
			var queueInfo = VkDeviceQueueCreateInfo.calloc(1, stack);
			queueInfo.position(0).sType$Default()
			         .queueFamilyIndex(family.index)
			         .pQueuePriorities(stack.floats(decreasingPriority(family.queueCount)));
			
			
			var features = VkPhysicalDeviceFeatures.calloc(stack)
			                                       .geometryShader(true);
			
			
			var info = VkDeviceCreateInfo.calloc(stack).sType$Default()
			                             .pQueueCreateInfos(queueInfo)
			                             .ppEnabledExtensionNames(VUtils.UTF8ArrayOnStack(
				                             stack,
				                             KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME,
				                             KHRShaderDrawParameters.VK_KHR_SHADER_DRAW_PARAMETERS_EXTENSION_NAME
			                             ))
			                             .pEnabledFeatures(features);
			
			var vkd = VKCalls.vkCreateDevice(pDevice, info);
			
			return new Device(vkd, this);
		}
	}
	
	private static float[] decreasingPriority(int count){
		var res = new float[count];
		for(int i = 0; i<count; i++){
			res[i] = (count - i)/(float)count;
		}
		return res;
	}
	
	@Override
	public String toString(){
		return name + " (" + type + ")";
	}
	
	@Override
	public boolean equals(Object obj){
		return obj instanceof PhysicalDevice that &&
		       this.pDevice.equals(that.pDevice);
	}
}
