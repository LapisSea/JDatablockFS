package com.lapissea.dfs.inspect.display.vk.wrap;

import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.inspect.display.VUtils;
import com.lapissea.dfs.inspect.display.VulkanCodeException;
import com.lapissea.dfs.inspect.display.vk.Flags;
import com.lapissea.dfs.inspect.display.vk.VKCalls;
import com.lapissea.dfs.inspect.display.vk.enums.VkFormat;
import com.lapissea.dfs.inspect.display.vk.enums.VkMemoryPropertyFlag;
import com.lapissea.dfs.inspect.display.vk.enums.VkPhysicalDeviceType;
import com.lapissea.dfs.inspect.display.vk.enums.VkQueueFlag;
import com.lapissea.dfs.inspect.display.vk.enums.VkSampleCountFlag;
import com.lapissea.dfs.utils.iterableplus.IterablePP;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.UtilL;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTIndexTypeUint8;
import org.lwjgl.vulkan.KHR8bitStorage;
import org.lwjgl.vulkan.KHRIndexTypeUint8;
import org.lwjgl.vulkan.KHRShaderDrawParameters;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDevice16BitStorageFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceIndexTypeUint8Features;
import org.lwjgl.vulkan.VkPhysicalDeviceIndexTypeUint8FeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceIndexTypeUint8FeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

import java.io.File;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

public class PhysicalDevice{
	
	public record MemoryProperties(List<MemoryType> memoryTypes, List<MemoryHeap> memoryHeaps){ }
	
	public final VkPhysicalDevice       pDevice;
	public final String                 name;
	public final VkPhysicalDeviceType   type;
	public final List<QueueFamilyProps> queueFamilies;
	public final MemoryProperties       memoryProperties;
	public final VkSampleCountFlag      samples;
	public final long                   nonCoherentAtomSize;
	
	public PhysicalDevice(VkPhysicalDevice pDevice){
		this.pDevice = pDevice;
		try(var stack = MemoryStack.stackPush()){
			var properties = VkPhysicalDeviceProperties.malloc(stack);
			VK10.vkGetPhysicalDeviceProperties(pDevice, properties);
			
			name = properties.deviceNameString();
			type = VkPhysicalDeviceType.from(properties.deviceType());
			
			samples = findReasonableSamples(properties);
			nonCoherentAtomSize = properties.limits().nonCoherentAtomSize();
		}
		
		queueFamilies = getQueueFamilies(pDevice);
		
		memoryProperties = getMemoryProperties();
		
	}
	
	private WeakReference<Set<String>> nameCache = new WeakReference<>(null);
	
	public Set<String> getDeviceExtensionNames(){
		if(nameCache.get() instanceof Set<String> cached){
			return cached;
		}
		Set<String> names;
		try(var stack = MemoryStack.stackPush()){
			var count = stack.mallocInt(1);
			VK10.vkEnumerateDeviceExtensionProperties(pDevice, (ByteBuffer)null, count, null);
			try(var props = VkExtensionProperties.malloc(count.get(0))){
				VK10.vkEnumerateDeviceExtensionProperties(pDevice, (ByteBuffer)null, count, props);
				
				names = Iters.from(props).map(VkExtensionProperties::extensionNameString).toSet();
				nameCache = new WeakReference<>(names);
			}
		}
		return names;
	}
	
	private VkSampleCountFlag findReasonableSamples(VkPhysicalDeviceProperties properties){
		var limits = properties.limits();
		var counts = VkSampleCountFlag.from(limits.framebufferColorSampleCounts()&limits.framebufferDepthSampleCounts());
		return counts.isEmpty()? VkSampleCountFlag.N1 : counts.getLast();
	}
	
	private List<QueueFamilyProps> getQueueFamilies(VkPhysicalDevice pDevice){
		try(var stack = MemoryStack.stackPush()){
			var ib = stack.mallocInt(1);
			VK10.vkGetPhysicalDeviceQueueFamilyProperties(pDevice, ib, null);
			var familyProps = VkQueueFamilyProperties.malloc(ib.get(0), stack);
			VK10.vkGetPhysicalDeviceQueueFamilyProperties(pDevice, ib, familyProps);
			
			return Iters.from(familyProps).enumerate(QueueFamilyProps::new).toList();
		}
	}
	
	private MemoryProperties getMemoryProperties(){
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
	
	public record MemoryTypeRes(int index, MemoryType type){ }
	
	public int getMemoryTypeIndex(int typeBits, Flags<VkMemoryPropertyFlag> requiredProperties){
		return getMemoryType(typeBits, requiredProperties).index;
	}
	public MemoryTypeRes getMemoryType(int typeBits, Flags<VkMemoryPropertyFlag> requiredProperties){
		return Iters.from(memoryProperties.memoryTypes())
		            .enumerate(MemoryTypeRes::new)
		            .firstMatching(e -> {
			            var typeSupported = UtilL.checkFlag(typeBits, 1<<e.index);
			            var hasMemProps   = e.type.propertyFlags.containsAll(requiredProperties);
			            return typeSupported && hasMemProps;
		            })
		            .orElseThrow(() -> new IllegalStateException("Could not find memory type for: " + typeBits + " & " + requiredProperties));
	}
	
	
	public Device createDevice(List<QueueFamilyProps> queueFamilies, File pipelineCacheFile) throws VulkanCodeException{
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
			
			var optionalFeatures = checkFeatures(Set.of());
			optionalFeatures.arithmeticTypesError.ifPresent(e -> Log.warn("Using GPU without arithmetic types! Reason:\n  {}#red", e));
			
			if(optionalFeatures.indexTypeUint8().isEmpty()) Log.warn("Using GPU without 8 bit indices");
			
			boolean hasArithmeticTypes = optionalFeatures.arithmeticTypesError.isEmpty();
			
			var features16bit = VkPhysicalDevice16BitStorageFeatures.calloc(stack).sType$Default();
			if(hasArithmeticTypes){
				features16bit.storageBuffer16BitAccess(true)
				             .uniformAndStorageBuffer16BitAccess(true);
			}
			
			var features12 = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default();
			if(hasArithmeticTypes){
				features12.shaderInt8(true)
				          .uniformAndStorageBuffer8BitAccess(true)
				          .shaderFloat16(true)
				          .storageBuffer8BitAccess(true);
			}
			
			var features = VkPhysicalDeviceFeatures.calloc(stack)
			                                       .sampleRateShading(true)
			                                       .multiDrawIndirect(true);
			if(hasArithmeticTypes){
				features.shaderInt16(true);
			}
			
			List<String> extensionNames = new ArrayList<>();
			extensionNames.add(KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME);
			extensionNames.add(KHRShaderDrawParameters.VK_KHR_SHADER_DRAW_PARAMETERS_EXTENSION_NAME);
			if(hasArithmeticTypes){
				extensionNames.add(KHR8bitStorage.VK_KHR_8BIT_STORAGE_EXTENSION_NAME);
			}
			
			optionalFeatures.indexTypeUint8.ifPresent(e -> extensionNames.add(e.extension));
			
			var info = VkDeviceCreateInfo.calloc(stack);
			info.sType$Default()
			    .pNext(features16bit)
			    .pNext(features12)
			    .pQueueCreateInfos(queueInfo)
			    .ppEnabledExtensionNames(VUtils.UTF8ArrayOnStack(stack, extensionNames))
			    .pEnabledFeatures(features);
			
			optionalFeatures.indexTypeUint8.ifPresent(e -> {
				var feature = e.feature.apply(stack).sType$Default();
				feature.indexTypeUint8(true);
				info.pNext(feature);
			});
			
			var vkd = VKCalls.vkCreateDevice(pDevice, info);
			
			return new Device(
				vkd, this, queueFamilies, hasArithmeticTypes,
				pipelineCacheFile, optionalFeatures.indexTypeUint8().isPresent()
			);
		}
	}
	
	public record IndexTypeUInt8Support(String extension, Function<MemoryStack, VkPhysicalDeviceIndexTypeUint8Features> feature){ }
	
	public record OptionalFeatures(Optional<String> arithmeticTypesError, Optional<IndexTypeUInt8Support> indexTypeUint8){ }
	
	public OptionalFeatures checkFeatures(Set<VkQueueFlag> requiredCapabilities){
		
		var combinedCaps = Iters.from(queueFamilies).map(e -> e.capabilities).reduce(Flags.of(), Flags::join);
		if(!combinedCaps.containsAll(requiredCapabilities)){
			throw new IllegalStateException(Log.fmt(
				"""
					Required capabilities are missing!
					  Requires : {}#yellow
					  But has  : {}#red""",
				Iters.from(VkQueueFlag.class).filter(requiredCapabilities::contains).joinAsStr(", "),
				Iters.from(combinedCaps).joinAsStr(", "))
			);
		}
		
		try(var stack = MemoryStack.stackPush()){
			var features = VkPhysicalDeviceFeatures.calloc(stack);
			VK11.vkGetPhysicalDeviceFeatures(pDevice, features);
			
			List<String> arithmeticTypesError = new ArrayList<>();
			
			if(!features.sampleRateShading()){
				throw new IllegalStateException("Does not support sampleRateShading");
			}
			if(!features.multiDrawIndirect()){
				throw new IllegalStateException("Does not support multiDrawIndirect");
			}
			
			if(!features.shaderInt16()){
				arithmeticTypesError.add("shaderInt16");
			}
			
			var features16bit = VkPhysicalDevice16BitStorageFeatures.calloc(stack).sType$Default();
			var features12    = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default();
			
			VK11.vkGetPhysicalDeviceFeatures2(
				pDevice,
				VkPhysicalDeviceFeatures2.calloc(stack)
				                         .sType$Default()
				                         .pNext(features12)
				                         .pNext(features16bit)
			);
			
			if(Boolean.getBoolean("disableArithmeticTypes")){
				arithmeticTypesError.add("Configuration disabled this feature");
			}
			
			if(!features16bit.storageBuffer16BitAccess()){
				arithmeticTypesError.add("storageBuffer16BitAccess");
			}
			if(!features16bit.uniformAndStorageBuffer16BitAccess()){
				arithmeticTypesError.add("uniformAndStorageBuffer16BitAccess");
			}
			if(!features12.shaderInt8()){
				arithmeticTypesError.add("shaderInt8");
			}
			if(!features12.shaderFloat16()){
				arithmeticTypesError.add("shaderFloat16");
			}
			if(!features12.uniformAndStorageBuffer8BitAccess()){
				arithmeticTypesError.add("uniformAndStorageBuffer8BitAccess");
			}
			if(!features12.storageBuffer8BitAccess()){
				arithmeticTypesError.add("storageBuffer8BitAccess");
			}
			
			var extensions = getDeviceExtensionNames();
			
			if(!extensions.contains(KHR8bitStorage.VK_KHR_8BIT_STORAGE_EXTENSION_NAME)){
				arithmeticTypesError.add(KHR8bitStorage.VK_KHR_8BIT_STORAGE_EXTENSION_NAME);
			}
			
			Optional<IndexTypeUInt8Support> indexTypeUInt8Support =
				Iters.of(new IndexTypeUInt8Support(KHRIndexTypeUint8.VK_KHR_INDEX_TYPE_UINT8_EXTENSION_NAME, VkPhysicalDeviceIndexTypeUint8FeaturesKHR::calloc),
				         new IndexTypeUInt8Support(EXTIndexTypeUint8.VK_EXT_INDEX_TYPE_UINT8_EXTENSION_NAME, VkPhysicalDeviceIndexTypeUint8FeaturesEXT::calloc)
				     ).filter(s -> extensions.contains(s.extension))
				     .firstMatching(s -> {
					     try(var stacc = MemoryStack.stackPush()){
						     var feature = s.feature.apply(stacc).sType$Default();
						     VK11.vkGetPhysicalDeviceFeatures2(
							     pDevice,
							     VkPhysicalDeviceFeatures2.calloc(stacc).sType$Default().pNext(feature)
						     );
						     return feature.indexTypeUint8();
					     }
				     });
			
			
			if(!extensions.contains(KHR8bitStorage.VK_KHR_8BIT_STORAGE_EXTENSION_NAME)){
				arithmeticTypesError.add(KHR8bitStorage.VK_KHR_8BIT_STORAGE_EXTENSION_NAME);
			}
			
			return new OptionalFeatures(
				toErr(arithmeticTypesError),
				indexTypeUInt8Support
			);
		}
	}
	
	private static Optional<String> toErr(List<String> arithmeticTypesError){
		return Iters.from(arithmeticTypesError).joinAsOptionalStr(", ", "Does not support: ", "");
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
	
	public VkFormatProperties getFormatProperties(MemoryStack stack, VkFormat format){
		var formatProps = VkFormatProperties.malloc(stack);
		VK10.vkGetPhysicalDeviceFormatProperties(pDevice, format.id, formatProps);
		return formatProps;
	}
	public long alignToAtomSizeDown(long size){
		return (size/nonCoherentAtomSize)*nonCoherentAtomSize;
	}
	public long alignToAtomSizeUp(long size){
		return Math.ceilDiv(size, nonCoherentAtomSize)*nonCoherentAtomSize;
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
