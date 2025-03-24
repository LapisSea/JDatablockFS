package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.tools.newlogger.display.VUtils;
import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.Flags;
import com.lapissea.dfs.tools.newlogger.display.vk.VKCalls;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VKPresentMode;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkMemoryPropertyFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkPhysicalDeviceType;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkSampleCountFlag;
import com.lapissea.dfs.utils.iterableplus.IterablePP;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.dfs.utils.iterableplus.Match;
import com.lapissea.util.UtilL;
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
import java.util.Optional;
import java.util.SequencedSet;
import java.util.Set;

public class PhysicalDevice{
	
	public record MemoryProperties(List<MemoryType> memoryTypes, List<MemoryHeap> memoryHeaps){ }
	
	public final VkPhysicalDevice          pDevice;
	public final String                    name;
	public final VkPhysicalDeviceType      type;
	public final List<QueueFamilyProps>    families;
	public final SequencedSet<FormatColor> formats;
	public final Set<VKPresentMode>        presentModes;
	public final MemoryProperties          memoryProperties;
	public final VkSampleCountFlag         samples;
	
	public PhysicalDevice(VkPhysicalDevice pDevice, Surface surface) throws VulkanCodeException{
		this.pDevice = pDevice;
		try(var stack = MemoryStack.stackPush()){
			var properties = VkPhysicalDeviceProperties.malloc(stack);
			VK10.vkGetPhysicalDeviceProperties(pDevice, properties);
			
			name = properties.deviceNameString();
			type = VkPhysicalDeviceType.from(properties.deviceType());
			
			samples = findReasonableSamples(properties);
		}
		
		families = getQueueFamilies(pDevice, surface);
		
		formats = surface.getFormats(pDevice);
		presentModes = Collections.unmodifiableSet(surface.getPresentModes(pDevice));
		
		memoryProperties = getMemoryProperties(pDevice);
	}
	
	private VkSampleCountFlag findReasonableSamples(VkPhysicalDeviceProperties properties){
		var limits     = properties.limits();
		var counts     = VkSampleCountFlag.from(limits.framebufferColorSampleCounts()&limits.framebufferDepthSampleCounts());
		var maxSamples = counts.isEmpty()? VkSampleCountFlag.N1 : counts.getLast();
		return switch(maxSamples){
			case N1, N2, N4, N8 -> maxSamples;
			case N16, N32, N64 -> VkSampleCountFlag.N8;
		};
	}
	
	private List<QueueFamilyProps> getQueueFamilies(VkPhysicalDevice pDevice, Surface surface) throws VulkanCodeException{
		try(var stack = MemoryStack.stackPush()){
			var ib = stack.mallocInt(1);
			VK10.vkGetPhysicalDeviceQueueFamilyProperties(pDevice, ib, null);
			var familyProps = VkQueueFamilyProperties.malloc(ib.get(0), stack);
			VK10.vkGetPhysicalDeviceQueueFamilyProperties(pDevice, ib, familyProps);
			
			var families = new ArrayList<QueueFamilyProps>();
			for(int i = 0; i<familyProps.capacity(); i++){
				var supportsPresent = surface.supportsPresent(pDevice, i);
				var familyProp      = familyProps.get(i);
				families.add(new QueueFamilyProps(supportsPresent, familyProp, i));
			}
			return List.copyOf(families);
		}
	}
	
	private MemoryProperties getMemoryProperties(VkPhysicalDevice pDevice){
		final MemoryProperties memoryProperties;
		try(var stack = MemoryStack.stackPush()){
			var mem = VkPhysicalDeviceMemoryProperties.malloc(stack);
			VK10.vkGetPhysicalDeviceMemoryProperties(pDevice, mem);
			
			var memoryTypes = Iters.from(mem.memoryTypes()).map(MemoryType::new).toList();
			var memoryHeaps = Iters.from(mem.memoryHeaps()).map(MemoryHeap::new).toList();
			memoryProperties = new MemoryProperties(memoryTypes, memoryHeaps);
		}
		return memoryProperties;
	}
	
	public int getMemoryTypeIndex(int typeBits, Flags<VkMemoryPropertyFlag> requiredProperties){
		return Iters.from(memoryProperties.memoryTypes())
		            .enumerate()
		            .filter(e -> {
			            var typeSupported = UtilL.checkFlag(typeBits, 1<<e.index());
			            var hasMemProps   = e.val().propertyFlags.containsAll(requiredProperties);
			            return typeSupported && hasMemProps;
		            }).map(IterablePP.Idx::index)
		            .findFirst()
		            .orElseThrow(() -> new IllegalStateException("Could not find memory type for: " + typeBits + " & " + requiredProperties));
	}
	
	
	public Device createDevice(List<QueueFamilyProps> queueFamilies) throws VulkanCodeException{
		try(var stack = MemoryStack.stackPush()){
			
			var queueInfo = VkDeviceQueueCreateInfo.calloc(queueFamilies.size(), stack);
			for(int i = 0; i<queueFamilies.size(); i++){
				var family = queueFamilies.get(i);
				queueInfo.position(i)
				         .sType$Default()
				         .queueFamilyIndex(family.index)
				         .pQueuePriorities(stack.floats(decreasingPriority(family.queueCount)));
			}
			queueInfo.position(0);
			
			
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
			
			return new Device(vkd, this, queueFamilies);
		}
	}
	
	private static float[] decreasingPriority(int count){
		if(count == 1) return new float[]{1};
		var res = new float[count];
		var cm1 = count - 1;
		for(int i = 0; i<count; i++){
			res[i] = (cm1 - i)/(float)cm1;
		}
		return res;
	}
	
	public FormatColor chooseSwapchainFormat(boolean log, Iterable<FormatColor> preferred){
		return switch(Iters.from(preferred).firstMatchingM(formats::contains)){
			case Match.Some(var f) -> {
				if(log) Log.info("Found format: {}#green", f);
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
				
				if(log) Log.warn("Found no preferred formats! Using: {}#yellow", f);
				yield f.get();
			}
		};
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
