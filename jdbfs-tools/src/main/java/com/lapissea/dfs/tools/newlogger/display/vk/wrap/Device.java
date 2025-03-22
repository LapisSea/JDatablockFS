package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.Flags;
import com.lapissea.dfs.tools.newlogger.display.vk.VKCalls;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VKImageType;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VKPresentMode;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkBufferUsageFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkCullModeFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkDescriptorPoolCreateFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkFormat;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkFrontFace;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkImageLayout;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkImageUsageFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkPolygonMode;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkSampleCountFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkSharingMode;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.dfs.utils.iterableplus.Match;
import com.lapissea.util.TextUtil;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkExtent3D;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;
import org.lwjgl.vulkan.VkViewport;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.lwjgl.vulkan.VK10.VK_IMAGE_TILING_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_SHARING_MODE_EXCLUSIVE;

public class Device implements VulkanResource{
	
	public final VkDevice value;
	
	public final PhysicalDevice physicalDevice;
	
	private final Map<QueueFamilyProps, Integer> familyAllocIndexes;
	
	public Device(VkDevice value, PhysicalDevice physicalDevice, List<QueueFamilyProps> queueFamilies){
		this.value = value;
		this.physicalDevice = physicalDevice;
		this.familyAllocIndexes = Iters.from(queueFamilies).toModMap(e -> e, e -> -1);
		if(!physicalDevice.pDevice.equals(value.getPhysicalDevice())){
			throw new IllegalArgumentException("physical device is not the argument");
		}
	}
	
	public Swapchain createSwapchain(Swapchain oldSwapchain, Surface surface, VKPresentMode preferredMode, Iterable<FormatColor> preferred) throws VulkanCodeException{
		try(var mem = MemoryStack.stackPush()){
			
			if(oldSwapchain == null) Log.info(TextUtil.toTable("Available formats", physicalDevice.formats));
			
			var available = physicalDevice.formats;
			
			var format = switch(Iters.from(preferred).firstMatchingM(available::contains)){
				case Match.Some(var f) -> {
					if(oldSwapchain == null) Log.info("Found format: {}#green", f);
					yield f;
				}
				case Match.None() -> {
					var f = Iters.from(available).firstMatching(fo -> Iters.from(preferred).map(fp -> fp.format).anyIs(fo.format));
					if(f.isEmpty()){
						f = Iters.from(available).firstMatching(fo -> Iters.from(preferred).map(fp -> fp.colorSpace).anyIs(fo.colorSpace));
					}
					if(f.isEmpty()){
						f = Optional.of(available.getFirst());
					}
					
					if(oldSwapchain == null) Log.warn("Found no preferred formats! Using: {}#yellow", f);
					yield f.get();
				}
			};
			
			
			SurfaceCapabilities surfaceCapabilities = surface.getCapabilities(physicalDevice);
			
			var presentMode = physicalDevice.presentModes.contains(preferredMode)?
			                  preferredMode :
			                  physicalDevice.presentModes.iterator().next();
			
			int numOfImages = Math.min(surfaceCapabilities.minImageCount + 1, surfaceCapabilities.maxImageCount);
			
			var usage = VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT|VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
			
			var info = VkSwapchainCreateInfoKHR.calloc(mem).sType$Default();
			info.surface(surface.handle)
			    .minImageCount(numOfImages)
			    .imageFormat(format.format.id)
			    .imageColorSpace(format.colorSpace.id)
			    .imageExtent(surfaceCapabilities.currentExtent.toStack(mem))
			    .imageArrayLayers(1)
			    .imageUsage(usage)
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
		int queueIndex = familyAllocIndexes.compute(queueFamily, (q, c) -> c + 1);
		try(var stack = MemoryStack.stackPush()){
			var ptr = stack.mallocPointer(1);
			VK10.vkGetDeviceQueue(value, queueFamily.index, queueIndex, ptr);
			var vq = new VkQueue(ptr.get(0), value);
			return new VulkanQueue(this, queueFamily, vq);
		}
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
	
	
	public Pipeline createPipeline(
		RenderPass renderPass, int subpass,
		List<ShaderModule> modules,
		Rect2D viewport, Rect2D scissors,
		VkPolygonMode polygonMode, VkCullModeFlag cullMode, VkFrontFace frontFace,
		VkSampleCountFlag sampleCount,
		DescriptorSetLayout descriptorSetLayout
	) throws VulkanCodeException{
		try(var stack = MemoryStack.stackPush()){
			
			var shaderStages = VkPipelineShaderStageCreateInfo.calloc(modules.size(), stack);
			var main         = stack.UTF8("main");
			for(int i = 0; i<modules.size(); i++){
				var module = modules.get(i);
				shaderStages.get(i)
				            .sType$Default()
				            .stage(module.stage.bit)
				            .module(module.handle)
				            .pName(main);
			}
			
			var vertInput = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default();
			
			var inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default()
			                                                          .topology(VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST)
			                                                          .primitiveRestartEnable(false);
			
			var viewports = VkViewport.calloc(1, stack);
			viewports.x(viewport.x).y(viewport.y)
			         .width(viewport.width).height(viewport.height)
			         .minDepth(0).maxDepth(1);
			var pScissors = VkRect2D.calloc(1, stack);
			scissors.set(pScissors.get(0));
			
			var viewportState = VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default()
			                                                     .pViewports(viewports)
			                                                     .pScissors(pScissors);
			
			var rasterState = VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
			                                                        .polygonMode(polygonMode.id)
			                                                        .cullMode(cullMode.bit)
			                                                        .frontFace(frontFace.bit)
			                                                        .depthClampEnable(false)
			                                                        .rasterizerDiscardEnable(false)
			                                                        .lineWidth(1);
			
			var multisampleState = VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default()
			                                                           .rasterizationSamples(sampleCount.bit)
			                                                           .sampleShadingEnable(false)
			                                                           .minSampleShading(1);
			
			var blendAttachState = VkPipelineColorBlendAttachmentState.calloc(1, stack);
			blendAttachState.blendEnable(false)
			                .colorWriteMask(VK10.VK_COLOR_COMPONENT_R_BIT|VK10.VK_COLOR_COMPONENT_G_BIT|VK10.VK_COLOR_COMPONENT_B_BIT|
			                                VK10.VK_COLOR_COMPONENT_A_BIT);
			
			var colorBlendState = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default()
			                                                         .logicOpEnable(false)
			                                                         .logicOp(VK10.VK_LOGIC_OP_COPY)
			                                                         .pAttachments(blendAttachState)
			                                                         .blendConstants(stack.floats(1, 1, 1, 1));
			
			var pipelineInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
			                                             .pSetLayouts(stack.longs(descriptorSetLayout.handle));
			
			var layout = VKCalls.vkCreatePipelineLayout(this, pipelineInfo);
			
			
			var info = VkGraphicsPipelineCreateInfo.calloc(1, stack);
			info.sType$Default()
			    .pStages(shaderStages)
			    .pVertexInputState(vertInput)
			    .pInputAssemblyState(inputAssembly)
			    .pViewportState(viewportState)
			    .pRasterizationState(rasterState)
			    .pMultisampleState(multisampleState)
			    .pColorBlendState(colorBlendState)
			    .layout(layout.handle)
			    .renderPass(renderPass.handle)
			    .subpass(subpass)
			    .basePipelineHandle(0)
			    .basePipelineIndex(-1);
			
			return VKCalls.vkCreateGraphicsPipelines(this, 0, info).getFirst();
		}
	}
	
	
	public DescriptorPool createDescriptorPool(int maxSets, Flags<VkDescriptorPoolCreateFlag> flags) throws VulkanCodeException{
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
	
	@Override
	public void destroy(){
		VK10.vkDestroyDevice(value, null);
	}
	
	@Override
	public String toString(){
		return "Device{" + physicalDevice.name + "}";
	}
}
