package com.lapissea.dfs.tools.newlogger.display.vk;

import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkBufferUsageFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkShaderStageFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.CommandPool;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Descriptor;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Device;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.FrameBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Pipeline;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.RenderPass;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.ShaderModule;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Surface;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.SurfaceCapabilities;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Swapchain;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkDeviceMemory;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkFence;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkImage;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkImageView;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkSampler;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkSemaphore;
import com.lapissea.dfs.utils.iterableplus.Iters;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTDebugUtils;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCreateInfoEXT;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkFramebufferCreateInfo;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkLayerProperties;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkRenderPassCreateInfo;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.List;

public interface VKCalls{
	
	static void check(int errorCode, String action) throws VulkanCodeException{
		if(errorCode == VK10.VK_SUCCESS){
			return;
		}
		throw VulkanCodeException.from(errorCode, action);
	}
	
	static int vkAcquireNextImageKHR(VkDevice device, Swapchain swapchain, long timeout, VkSemaphore semaphore, VkFence fence) throws VulkanCodeException{
		var index = new int[1];
		check(KHRSwapchain.vkAcquireNextImageKHR(
			device, swapchain.handle, timeout, semaphore == null? 0 : semaphore.handle, fence == null? 0 : fence.handle, index
		), "vkAcquireNextImageKHR");
		return index[0];
	}
	static void vkQueueSubmit(VkQueue queue, VkSubmitInfo info, VkFence fence) throws VulkanCodeException{
		check(VK10.vkQueueSubmit(queue, info, fence == null? 0 : fence.handle), "vkQueueSubmit");
	}
	static void vkQueuePresentKHR(VkQueue queue, VkPresentInfoKHR info) throws VulkanCodeException{
		check(KHRSwapchain.vkQueuePresentKHR(queue, info), "vkQueuePresentKHR");
	}
	static void vkQueueWaitIdle(VkQueue queue) throws VulkanCodeException{
		check(VK10.vkQueueWaitIdle(queue), "vkQueueWaitIdle");
	}
	static VkInstance vkCreateInstance(MemoryStack stack, VkInstanceCreateInfo info) throws VulkanCodeException{
		var pp = stack.mallocPointer(1);
		check(VK10.vkCreateInstance(info, null, pp), "vkCreateInstance");
		return new VkInstance(pp.get(0), info);
	}
	static void vkEnumerateInstanceExtensionProperties(CharSequence pLayerName, IntBuffer pPropertyCount, VkExtensionProperties.Buffer pProperties) throws VulkanCodeException{
		check(VK10.vkEnumerateInstanceExtensionProperties(pLayerName, pPropertyCount, pProperties), "vkEnumerateInstanceExtensionProperties");
	}
	static Swapchain vkCreateSwapchainKHR(Device device, VkSwapchainCreateInfoKHR createInfo) throws VulkanCodeException{
		var ptr = new long[1];
		check(KHRSwapchain.vkCreateSwapchainKHR(device.value, createInfo, null, ptr), "vkCreateSwapchainKHR");
		return new Swapchain(device, ptr[0], createInfo);
	}
	static void vkGetSwapchainImagesKHR(Device device, Swapchain swapchain, IntBuffer pSwapchainImageCount, LongBuffer pSwapchainImages) throws VulkanCodeException{
		check(KHRSwapchain.vkGetSwapchainImagesKHR(device.value, swapchain.handle, pSwapchainImageCount, pSwapchainImages), "vkGetSwapchainImagesKHR");
	}
	static VkImageView vkCreateImageView(Device device, VkImageViewCreateInfo info) throws VulkanCodeException{
		var ptr = new long[1];
		check(VK10.vkCreateImageView(device.value, info, null, ptr), "vkCreateImageView");
		return new VkImageView(device, ptr[0]);
	}
	static long vkCreateCommandPool(Device device, VkCommandPoolCreateInfo info) throws VulkanCodeException{
		var ptr = new long[1];
		check(VK10.vkCreateCommandPool(device.value, info, null, ptr), "vkCreateCommandPool");
		var res = ptr[0];
		return res;
	}
	static VkSemaphore vkCreateSemaphore(Device device, VkSemaphoreCreateInfo info) throws VulkanCodeException{
		var ptr = new long[1];
		check(VK10.vkCreateSemaphore(device.value, info, null, ptr), "vkCreateSemaphore");
		return new VkSemaphore(device, ptr[0]);
	}
	static void vkBeginCommandBuffer(VkCommandBuffer commandBuffer, VkCommandBufferBeginInfo info) throws VulkanCodeException{
		check(VK10.vkBeginCommandBuffer(commandBuffer, info), "vkBeginCommandBuffer");
	}
	static void vkEndCommandBuffer(VkCommandBuffer commandBuffer) throws VulkanCodeException{
		check(VK10.vkEndCommandBuffer(commandBuffer), "vkEndCommandBuffer");
	}
	static void vkResetCommandBuffer(VkCommandBuffer commandBuffer, int flags) throws VulkanCodeException{
		check(VK10.vkResetCommandBuffer(commandBuffer, flags), "vkResetCommandBuffer");
	}
	static void vkAllocateCommandBuffers(Device device, VkCommandBufferAllocateInfo pAllocateInfo, PointerBuffer pCommandBuffers) throws VulkanCodeException{
		check(VK10.vkAllocateCommandBuffers(device.value, pAllocateInfo, pCommandBuffers), "vkAllocateCommandBuffers");
	}
	static Surface glfwCreateWindowSurface(VkInstance instance, long windowHandle) throws VulkanCodeException{
		var surfaceRef = new long[1];
		check(GLFWVulkan.glfwCreateWindowSurface(instance, windowHandle, null, surfaceRef), "glfwCreateWindowSurface");
		return new Surface(instance, surfaceRef[0]);
	}
	static boolean vkGetPhysicalDeviceSurfaceSupportKHR(VkPhysicalDevice device, int familyIndex, Surface surface) throws VulkanCodeException{
		var res = new int[1];
		check(KHRSurface.vkGetPhysicalDeviceSurfaceSupportKHR(device, familyIndex, surface.handle, res), "vkGetPhysicalDeviceSurfaceSupportKHR");
		return res[0] == VK10.VK_TRUE;
	}
	static void vkGetPhysicalDeviceSurfaceFormatsKHR(VkPhysicalDevice physicalDevice, Surface surface, IntBuffer pSurfaceFormatCount, VkSurfaceFormatKHR.Buffer pSurfaceFormats) throws VulkanCodeException{
		check(KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(physicalDevice, surface.handle, pSurfaceFormatCount, pSurfaceFormats), "vkGetPhysicalDeviceSurfaceFormatsKHR");
	}
	static void vkGetPhysicalDeviceSurfacePresentModesKHR(VkPhysicalDevice physicalDevice, Surface surface, IntBuffer pPresentModeCount, IntBuffer pPresentModes) throws VulkanCodeException{
		check(KHRSurface.vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, surface.handle, pPresentModeCount, pPresentModes), "vkGetPhysicalDeviceSurfacePresentModesKHR");
	}
	
	static SurfaceCapabilities vkGetPhysicalDeviceSurfaceCapabilitiesKHR(VkPhysicalDevice physicalDevice, long surfaceHandle) throws VulkanCodeException{
		try(var mem = MemoryStack.stackPush()){
			var caps = VkSurfaceCapabilitiesKHR.malloc(mem);
			check(KHRSurface.vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice, surfaceHandle, caps), "vkGetPhysicalDeviceSurfaceCapabilitiesKHR");
			return new SurfaceCapabilities(caps);
		}
	}
	static VkDevice vkCreateDevice(VkPhysicalDevice physicalDevice, VkDeviceCreateInfo info) throws VulkanCodeException{
		try(var mem = MemoryStack.stackPush()){
			var ptr = mem.mallocPointer(1);
			check(VK10.vkCreateDevice(physicalDevice, info, null, ptr), "vkCreateDevice");
			return new VkDevice(ptr.get(0), physicalDevice, info);
		}
	}
	static void vkEnumerateInstanceLayerProperties(IntBuffer pPropertyCount, VkLayerProperties.Buffer pProperties) throws VulkanCodeException{
		check(VK10.vkEnumerateInstanceLayerProperties(pPropertyCount, pProperties), "vkEnumerateInstanceLayerProperties");
	}
	static long vkCreateDebugUtilsMessengerEXT(VkInstance instance, VkDebugUtilsMessengerCreateInfoEXT cInfo) throws VulkanCodeException{
		var callbackB = new long[1];
		check(EXTDebugUtils.vkCreateDebugUtilsMessengerEXT(instance, cInfo, null, callbackB), "createDebugUtilsMessenger");
		return callbackB[0];
	}
	static void vkEnumeratePhysicalDevices(VkInstance instance, IntBuffer pPhysicalDeviceCount, PointerBuffer pPhysicalDevices) throws VulkanCodeException{
		check(VK10.vkEnumeratePhysicalDevices(instance, pPhysicalDeviceCount, pPhysicalDevices), "vkEnumeratePhysicalDevices");
	}
	static void vkFreeCommandBuffers(CommandPool pool, VkCommandBuffer commandBuffer){
		VK10.vkFreeCommandBuffers(pool.device.value, pool.handle, commandBuffer);
	}
	static RenderPass vkCreateRenderPass(Device device, VkRenderPassCreateInfo pCreateInfo) throws VulkanCodeException{
		var res = new long[1];
		check(VK10.vkCreateRenderPass(device.value, pCreateInfo, null, res), "vkCreateRenderPass");
		return new RenderPass(device, res[0]);
	}
	static FrameBuffer vkCreateFramebuffer(Device device, VkFramebufferCreateInfo info) throws VulkanCodeException{
		var ref = new long[1];
		check(VK10.vkCreateFramebuffer(device.value, info, null, ref), "vkCreateFramebuffer");
		return new FrameBuffer(device, ref[0]);
	}
	static ShaderModule vkCreateShaderModule(Device device, VkShaderStageFlag stage, VkShaderModuleCreateInfo pCreateInfo) throws VulkanCodeException{
		var res = new long[1];
		check(VK10.vkCreateShaderModule(device.value, pCreateInfo, null, res), "vkCreateShaderModule");
		return new ShaderModule(device, res[0], stage);
	}
	static List<Pipeline> vkCreateGraphicsPipelines(Device device, long pipelineCache, VkGraphicsPipelineCreateInfo.Buffer pCreateInfos) throws VulkanCodeException{
		long[] result = new long[pCreateInfos.capacity()];
		check(VK10.vkCreateGraphicsPipelines(device.value, pipelineCache, pCreateInfos, null, result), "vkCreateGraphicsPipelines");
		return Iters.rangeMap(0, result.length, i -> {
			var layout = new Pipeline.Layout(device, pCreateInfos.get(i).layout());
			return new Pipeline(device, result[i], layout);
		}).toList();
	}
	static Pipeline.Layout vkCreatePipelineLayout(Device device, VkPipelineLayoutCreateInfo pCreateInfo) throws VulkanCodeException{
		long[] res = new long[1];
		check(VK10.vkCreatePipelineLayout(device.value, pCreateInfo, null, res), "vkCreatePipelineLayout");
		return new Pipeline.Layout(device, res[0]);
	}
	static MappedVkMemory vkMapMemory(VkDeviceMemory memory, long offset, long size, int flags) throws VulkanCodeException{
		try(var stack = MemoryStack.stackPush()){
			var res = stack.mallocPointer(1);
			check(VK10.vkMapMemory(memory.device.value, memory.handle, offset, size, flags, res), "vkMapMemory");
			return new MappedVkMemory(memory, res.get(0), size);
		}
	}
	static VkBuffer vkCreateBuffer(Device device, VkBufferCreateInfo info) throws VulkanCodeException{
		var res = new long[1];
		check(VK10.vkCreateBuffer(device.value, info, null, res), "vkCreateBuffer");
		return new VkBuffer(device, res[0], info.size(), VkBufferUsageFlag.from(info.usage()));
	}
	static VkDeviceMemory vkAllocateMemory(Device device, VkMemoryAllocateInfo pAllocateInfo) throws VulkanCodeException{
		var res = new long[1];
		check(VK10.vkAllocateMemory(device.value, pAllocateInfo, null, res), "vkAllocateMemory");
		var memoryTypes = device.physicalDevice.memoryProperties.memoryTypes();
		var type        = memoryTypes.get(pAllocateInfo.memoryTypeIndex());
		return new VkDeviceMemory(device, res[0], pAllocateInfo.allocationSize(), type.propertyFlags);
	}
	static void vkBindBufferMemory(VkBuffer buffer, VkDeviceMemory memoryPtr, long memoryOffset) throws VulkanCodeException{
		check(VK10.vkBindBufferMemory(buffer.device.value, buffer.handle, memoryPtr.handle, memoryOffset), "vkBindBufferMemory");
	}
	static void vkBindImageMemory(VkImage image, VkDeviceMemory memoryPtr, long memoryOffset) throws VulkanCodeException{
		check(VK10.vkBindImageMemory(image.device.value, image.handle, memoryPtr.handle, memoryOffset), "vkBindBufferMemory");
	}
	static Descriptor.VkLayout vkCreateDescriptorSetLayout(Descriptor.VkPool pool, VkDescriptorSetLayoutCreateInfo pCreateInfo) throws VulkanCodeException{
		var res = new long[1];
		check(VK10.vkCreateDescriptorSetLayout(pool.device.value, pCreateInfo, null, res), "vkCreateDescriptorSetLayout");
		return new Descriptor.VkLayout(pool, res[0]);
	}
	static Descriptor.VkPool vkCreateDescriptorPool(Device device, VkDescriptorPoolCreateInfo pCreateInfo) throws VulkanCodeException{
		var res = new long[1];
		check(VK10.vkCreateDescriptorPool(device.value, pCreateInfo, null, res), "vkCreateDescriptorPool");
		return new Descriptor.VkPool(device, res[0]);
	}
	static List<Descriptor.VkSet> vkAllocateDescriptorSets(Device device, VkDescriptorSetAllocateInfo pAllocateInfo) throws VulkanCodeException{
		long[] arr = new long[pAllocateInfo.descriptorSetCount()];
		check(VK10.vkAllocateDescriptorSets(device.value, pAllocateInfo, arr), "vkAllocateDescriptorSets");
		var res = new Descriptor.VkSet[arr.length];
		for(int i = 0; i<arr.length; i++){
			res[i] = new Descriptor.VkSet(device, arr[i]);
		}
		return List.of(res);
	}
	static VkImage vkCreateImage(Device device, VkImageCreateInfo pCreateInfo) throws VulkanCodeException{
		var res = new long[1];
		check(VK10.vkCreateImage(device.value, pCreateInfo, null, res), "vkCreateImage");
		return new VkImage(device, res[0], pCreateInfo);
	}
	static VkSampler vkCreateSampler(Device device, VkSamplerCreateInfo pCreateInfo) throws VulkanCodeException{
		var res = new long[1];
		check(VK10.vkCreateSampler(device.value, pCreateInfo, null, res), "vkCreateSampler");
		return new VkSampler(device, res[0]);
	}
	static VkFence vkCreateFence(Device device, VkFenceCreateInfo pCreateInfo) throws VulkanCodeException{
		var res = new long[1];
		check(VK10.vkCreateFence(device.value, pCreateInfo, null, res), "vkCreateFence");
		return new VkFence(device, res[0]);
	}
	static void vkWaitForFence(VkDevice device, VkFence fence, long timeout) throws VulkanCodeException{
		check(VK10.vkWaitForFences(device, fence.handle, true, timeout), "vkWaitForFences");
	}
	static void vkResetFences(VkDevice device, VkFence fence) throws VulkanCodeException{
		check(VK10.vkResetFences(device, fence.handle), "vkResetFences");
	}
}
