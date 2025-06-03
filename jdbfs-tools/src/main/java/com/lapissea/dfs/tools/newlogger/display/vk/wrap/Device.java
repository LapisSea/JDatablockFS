package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.BackedVkBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.Flags;
import com.lapissea.dfs.tools.newlogger.display.vk.TransferBuffers;
import com.lapissea.dfs.tools.newlogger.display.vk.VKCalls;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VKImageType;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VKPresentMode;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkBufferUsageFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkDescriptorPoolCreateFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkFilter;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkFormat;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkImageLayout;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkImageUsageFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkMemoryPropertyFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkSampleCountFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkSamplerAddressMode;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkSharingMode;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.function.UnsafeConsumer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkExtent3D;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkPipelineCacheCreateInfo;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.lwjgl.vulkan.VK10.VK_IMAGE_TILING_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_LOD_CLAMP_NONE;
import static org.lwjgl.vulkan.VK10.VK_SHARING_MODE_EXCLUSIVE;

public class Device implements VulkanResource{
	
	public final VkDevice value;
	
	public final PhysicalDevice physicalDevice;
	
	private final Map<QueueFamilyProps, Integer> familyAllocIndexes;
	
	public final Map<Long, Throwable> debugVkObjects = new ConcurrentHashMap<>();
	public final boolean              hasArithmeticTypes;
	
	public final VkPipelineCache pipelineCache;
	
	private final File pipelineCacheFile;
	
	public final VkFormat color8bitFormat;
	
	public Device(VkDevice value, PhysicalDevice physicalDevice, List<QueueFamilyProps> queueFamilies, boolean hasArithmeticTypes, File pipelineCacheFile) throws VulkanCodeException{
		this.value = value;
		this.physicalDevice = physicalDevice;
		this.familyAllocIndexes = Iters.from(queueFamilies).toModMap(e -> e, e -> -1);
		this.hasArithmeticTypes = hasArithmeticTypes;
		this.pipelineCacheFile = pipelineCacheFile;
		if(!physicalDevice.pDevice.equals(value.getPhysicalDevice())){
			throw new IllegalArgumentException("physical device is not the argument");
		}
		
		RandomAccessFile file = null;
		try(var mem = MemoryStack.stackPush()){
			var info = VkPipelineCacheCreateInfo.calloc(mem).sType$Default();
			
			try{
				file = new RandomAccessFile(pipelineCacheFile, "r");
				var channel = file.getChannel();
				var data    = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
				info.pInitialData(data);
			}catch(FileNotFoundException e){
				Log.info("No pipeline cache found");
			}catch(IOException e){
				e.printStackTrace();
			}
			
			pipelineCache = VKCalls.vkCreatePipelineCache(this, info);
		}finally{
			if(file != null){
				try{
					file.close();
				}catch(IOException e){
					e.printStackTrace();
				}
			}
		}
		color8bitFormat = hasArithmeticTypes? VkFormat.R8G8B8A8_UINT : VkFormat.R32_UINT;
	}
	
	public Swapchain createSwapchain(Swapchain oldSwapchain, Surface surface, VKPresentMode preferredMode, Iterable<FormatColor> preferred) throws VulkanCodeException{
		//if(oldSwapchain == null) Log.info(TextUtil.toTable("Available formats", physicalDevice.formats));
		
		SurfaceCapabilities surfaceCapabilities = surface.getCapabilities(physicalDevice);
		if(surfaceCapabilities.currentExtent.width == 0 || surfaceCapabilities.currentExtent.height == 0){
			return null;
		}
		
		var presentModes = surface.getPresentModes(physicalDevice);
		
		var format = surface.chooseSwapchainFormat(physicalDevice, preferred);
		
		var presentMode = presentModes.contains(preferredMode)?
		                  preferredMode :
		                  presentModes.iterator().next();
		
		int numOfImages = Math.min(surfaceCapabilities.minImageCount + 1, surfaceCapabilities.maxImageCount);
		
		try(var mem = MemoryStack.stackPush()){
			var info = VkSwapchainCreateInfoKHR.calloc(mem).sType$Default();
			info.surface(surface.handle)
			    .minImageCount(numOfImages)
			    .imageFormat(format.format.id)
			    .imageColorSpace(format.colorSpace.id)
			    .imageExtent(surfaceCapabilities.currentExtent.toStack(mem))
			    .imageArrayLayers(1)
			    .imageUsage(VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT|VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT)
			    .imageSharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE)
			    .preTransform(surfaceCapabilities.currentTransform.bit)
			    .compositeAlpha(KHRSurface.VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
			    .presentMode(presentMode.id)
			    .clipped(true);
			if(oldSwapchain != null){
				info.oldSwapchain(oldSwapchain.handle);
			}
			
			return VKCalls.vkCreateSwapchainKHR(this, info);
		}
	}
	
	public VkImageView createImageView(VkImageViewCreateInfo info) throws VulkanCodeException{
		return VKCalls.vkCreateImageView(this, info);
	}
	
	public CommandPool createCommandPool(QueueFamilyProps queue, CommandPool.Type commandPoolType) throws VulkanCodeException{
		try(var mem = MemoryStack.stackPush()){
			int flags = 0;
			if(commandPoolType == CommandPool.Type.SHORT_LIVED) flags |= VK10.VK_COMMAND_POOL_CREATE_TRANSIENT_BIT;
			if(commandPoolType != CommandPool.Type.WRITE_ONCE) flags |= VK10.VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
			
			var info = VkCommandPoolCreateInfo.calloc(mem)
			                                  .sType$Default()
			                                  .flags(flags)
			                                  .queueFamilyIndex(queue.index);
			
			var res = VKCalls.vkCreateCommandPool(this, info);
			return new CommandPool(this, res, commandPoolType);
		}
	}
	
	
	public synchronized VulkanQueue allocateQueue(QueueFamilyProps queueFamily){
		if(!familyAllocIndexes.containsKey(queueFamily)){
			throw new IllegalArgumentException("Queue family not registered with device");
		}
		int queueIndex = familyAllocIndexes.compute(queueFamily, (q, c) -> {
			var nextIndex = c + 1;
			if(nextIndex>=q.queueCount){
				throw new UnsupportedOperationException("Ran out of queues for " + q);
			}
			return nextIndex;
		});
		VkQueue vq = VKCalls.vkGetDeviceQueue(value, queueFamily, queueIndex);
		return new VulkanQueue(this, queueFamily, vq);
	}
	
	public VkSemaphore[] createSemaphores(int count) throws VulkanCodeException{
		var res = new VkSemaphore[count];
		for(int i = 0; i<count; i++){
			res[i] = createSemaphore();
		}
		return res;
	}
	public VkSemaphore createSemaphore() throws VulkanCodeException{
		try(var stack = MemoryStack.stackPush()){
			
			var info = VkSemaphoreCreateInfo.calloc(stack)
			                                .sType$Default();
			
			return VKCalls.vkCreateSemaphore(this, info);
		}
	}
	
	public VkFence[] createFences(int count, boolean signaledInitial) throws VulkanCodeException{
		var res = new VkFence[count];
		for(int i = 0; i<count; i++){
			res[i] = createFence(signaledInitial);
		}
		return res;
	}
	public VkFence createFence(boolean signaledInitial) throws VulkanCodeException{
		try(var stack = MemoryStack.stackPush()){
			var info = VkFenceCreateInfo.malloc(stack);
			info.sType$Default()
			    .pNext(0)
			    .flags(signaledInitial? VK10.VK_FENCE_CREATE_SIGNALED_BIT : 0);
			
			return VKCalls.vkCreateFence(this, info);
		}
	}
	
	public RenderPass.Builder buildRenderPass(){
		return new RenderPass.Builder(this);
	}
	
	
	public VkDescriptorPool createDescriptorPool(int maxSets, VkDescriptorPoolCreateFlag flag) throws VulkanCodeException{
		return createDescriptorPool(maxSets, Flags.of(flag));
	}
	public VkDescriptorPool createDescriptorPool(int maxSets, Flags<VkDescriptorPoolCreateFlag> flags) throws VulkanCodeException{
		try(var stack = MemoryStack.stackPush()){
			
			var info = VkDescriptorPoolCreateInfo.calloc(stack);
			info.sType$Default()
			    .flags(flags.value)
			    .maxSets(maxSets);
			
			return VKCalls.vkCreateDescriptorPool(this, info);
		}
	}
	
	public VkImage createImage(int width, int height, VkFormat format, Flags<VkImageUsageFlag> usage, VkSampleCountFlag samples, int mipLevels) throws VulkanCodeException{
		
		try(var stack = MemoryStack.stackPush()){
			var info = VkImageCreateInfo.calloc(stack);
			info.sType$Default()
			    .imageType(VKImageType.IMG_2D.id)
			    .format(format.id)
			    .extent(VkExtent3D.malloc(stack).set(width, height, 1))
			    .mipLevels(mipLevels)
			    .arrayLayers(1)
			    .samples(samples.bit)
			    .tiling(VK_IMAGE_TILING_OPTIMAL)
			    .usage(usage.value)
			    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
			    .pQueueFamilyIndices(null)
			    .initialLayout(VkImageLayout.UNDEFINED.id);
			
			return VKCalls.vkCreateImage(this, info);
		}
	}
	
	public VkDeviceMemory allocateMemory(long size, int memoryTypeIndex) throws VulkanCodeException{
		try(var stack = MemoryStack.stackPush()){
			var info = VkMemoryAllocateInfo.malloc(stack);
			info.sType$Default()
			    .pNext(0)
			    .allocationSize(size)
			    .memoryTypeIndex(memoryTypeIndex);
			
			return VKCalls.vkAllocateMemory(this, info);
		}
	}
	public VkBuffer createBuffer(long size, Flags<VkBufferUsageFlag> usageFlags, VkSharingMode sharingMode) throws VulkanCodeException{
		try(var stack = MemoryStack.stackPush()){
			var info = VkBufferCreateInfo.calloc(stack);
			info.sType$Default()
			    .size(size)
			    .usage(usageFlags.value)
			    .sharingMode(sharingMode.id);
			
			return VKCalls.vkCreateBuffer(this, info);
		}
	}
	
	public BackedVkBuffer allocateStagingBuffer(long size) throws VulkanCodeException{
		return allocateBuffer(size, Flags.of(VkBufferUsageFlag.TRANSFER_SRC),
		                      Flags.of(VkMemoryPropertyFlag.HOST_VISIBLE, VkMemoryPropertyFlag.HOST_COHERENT));
	}
	public BackedVkBuffer allocateHostBuffer(long size, VkBufferUsageFlag usage) throws VulkanCodeException{
		return allocateBuffer(size, Flags.of(usage), Flags.of(VkMemoryPropertyFlag.HOST_VISIBLE, VkMemoryPropertyFlag.HOST_CACHED));
	}
	
	public <E extends Throwable> BackedVkBuffer allocateDeviceLocalBuffer(TransferBuffers transferBuffers, long size, UnsafeConsumer<ByteBuffer, E> populator) throws E, VulkanCodeException{
		try(var stagingVb = allocateStagingBuffer(size)){
			stagingVb.update(populator);
			var vb = allocateBuffer(
				size,
				Flags.of(VkBufferUsageFlag.STORAGE_BUFFER, VkBufferUsageFlag.TRANSFER_DST),
				Flags.of(VkMemoryPropertyFlag.DEVICE_LOCAL)
			);
			stagingVb.copyTo(transferBuffers, vb);
			return vb;
		}
	}
	public BackedVkBuffer allocateBuffer(long size, Flags<VkBufferUsageFlag> usageFlags, Flags<VkMemoryPropertyFlag> memoryFlags) throws VulkanCodeException{
		VkBuffer       buffer = null;
		VkDeviceMemory memory = null;
		try{
			buffer = createBuffer(size, usageFlags, VkSharingMode.EXCLUSIVE);
			
			var requirements = buffer.getRequirements();
			var requiredSize = requirements.size();
			if(requiredSize<size){
				throw new ShouldNeverHappenError("Required buffer size is too small");
			}else if(requiredSize != size){
				buffer.destroy();
				buffer = createBuffer(requiredSize, usageFlags, VkSharingMode.EXCLUSIVE);
				requirements = buffer.getRequirements();
				if(requirements.size() != requiredSize){
					throw new IllegalStateException(requirements + " size does not match " + requiredSize);
				}
			}
			
			memory = buffer.allocateAndBindRequiredMemory(physicalDevice, memoryFlags);
			
			return new BackedVkBuffer(buffer, memory);
		}catch(Throwable e){
			if(memory != null) memory.destroy();
			if(buffer != null) buffer.destroy();
			throw e;
		}
	}
	
	public VkSampler createSampler(VkFilter min, VkFilter mag, VkSamplerAddressMode samplerAddressMode) throws VulkanCodeException{
		try(var stack = MemoryStack.stackPush()){
			var info = VkSamplerCreateInfo.calloc(stack);
			info.sType$Default()
			    .minFilter(min.id)
			    .magFilter(mag.id)
			    .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_LINEAR)
			    .addressModeU(samplerAddressMode.id)
			    .addressModeV(samplerAddressMode.id)
			    .addressModeW(samplerAddressMode.id)
			    .mipLodBias(0)
			    .anisotropyEnable(false)
			    .maxAnisotropy(1)
			    .compareEnable(false)
			    .compareOp(VK10.VK_COMPARE_OP_ALWAYS)
			    .minLod(0)
			    .maxLod(VK_LOD_CLAMP_NONE)
			    .borderColor(VK10.VK_BORDER_COLOR_FLOAT_OPAQUE_BLACK)
			    .unnormalizedCoordinates(false);
			
			return VKCalls.vkCreateSampler(this, info);
		}
	}
	
	public void waitIdle(){
		VK10.vkDeviceWaitIdle(value);
	}
	
	@Override
	public void destroy(){
		try{
			pipelineCache.saveDataTo(pipelineCacheFile);
		}catch(Exception e){
			new RuntimeException("Failed to save cache data", e).printStackTrace();
		}
		
		pipelineCache.destroy();
		VK10.vkDestroyDevice(value, null);
	}
	
	@Override
	public String toString(){
		return "Device{" + physicalDevice.name + "}";
	}
}
