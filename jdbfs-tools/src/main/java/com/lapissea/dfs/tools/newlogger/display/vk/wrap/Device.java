package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.VKCalls;
import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VKPresentMode;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkColorSpaceKHR;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkCullModeFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkFormat;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkFrontFace;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkPolygonMode;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkSampleCountFlag;
import com.lapissea.dfs.utils.iterableplus.Iters;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
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

public class Device implements VulkanResource{
	
	public final VkDevice value;
	
	public final PhysicalDevice physicalDevice;
	
	public Device(VkDevice value, PhysicalDevice physicalDevice){
		this.value = value;
		this.physicalDevice = physicalDevice;
		if(!physicalDevice.pDevice.equals(value.getPhysicalDevice())){
			throw new IllegalArgumentException("physical device is not the argument");
		}
	}
	
	public Swapchain createSwapchain(Surface surface, VKPresentMode preferredPresentMode) throws VulkanCodeException{
		try(var mem = MemoryStack.stackPush()){
			var format = Iters.from(physicalDevice.formats)
			                  .firstMatching(e -> e.format == VkFormat.R8G8B8A8_UNORM && e.colorSpace == VkColorSpaceKHR.SRGB_NONLINEAR_KHR)
			                  .orElse(physicalDevice.formats.getFirst());
			
			SurfaceCapabilities surfaceCapabilities = surface.getCapabilities(physicalDevice);
			
			var presentMode = physicalDevice.presentModes.contains(preferredPresentMode)?
			                  preferredPresentMode :
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
			    .clipped(true)
			;
			
			return VKCalls.vkCreateSwapchainKHR(this, info);
		}
	}
	
	public ImageView createImageView(VkImageViewCreateInfo info) throws VulkanCodeException{
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
			return new CommandPool(res, this, commandPoolType);
		}
	}
	
	public VkQueue getQueue(QueueFamilyProps family, int queueIndex){
		try(var stack = MemoryStack.stackPush()){
			var ptr = stack.mallocPointer(1);
			VK10.vkGetDeviceQueue(value, family.index, queueIndex, ptr);
			return new VkQueue(ptr.get(0), value);
		}
	}
	
	public VulkanSemaphore createSemaphore() throws VulkanCodeException{
		try(var stack = MemoryStack.stackPush()){
			
			var info = VkSemaphoreCreateInfo.calloc(stack)
			                                .sType$Default();
			
			return VKCalls.vkCreateSemaphore(this, info);
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
		VkSampleCountFlag sampleCount
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
			                                                        .polygonMode(polygonMode.bit)
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
			
			var pCreateInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
			                                            .pSetLayouts(null);
			
			var layout = VKCalls.vkCreatePipelineLayout(this, pCreateInfo);
			
			
			var pCreateInfos = VkGraphicsPipelineCreateInfo.calloc(1, stack).sType$Default()
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
			
			return VKCalls.vkCreateGraphicsPipelines(this, 0, pCreateInfos).getFirst();
		}
	}
	
	@Override
	public void destroy(){
		VK10.vkDestroyDevice(value, null);
	}
}
