package com.lapissea.dfs.tools.newlogger.display.vk;

import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.CommandPool;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Device;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.FrameBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.ImageView;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.RenderPass;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Surface;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.SurfaceCapabilities;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Swapchain;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VulkanSemaphore;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTDebugUtils;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCreateInfoEXT;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkFramebufferCreateInfo;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkLayerProperties;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkRenderPassCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

public interface VKCalls{
	
	static void check(int errorCode, String action) throws VulkanCodeException{
		if(errorCode == VK10.VK_SUCCESS){
			return;
		}
		throw VulkanCodeException.from(errorCode, action);
	}
	
	static int vkAcquireNextImageKHR(VkDevice device, Swapchain swapchain, long timeout, VulkanSemaphore semaphore, long fence) throws VulkanCodeException{
		var index = new int[1];
		check(KHRSwapchain.vkAcquireNextImageKHR(device, swapchain.handle, timeout, semaphore.handle, fence, index), "vkAcquireNextImageKHR");
		return index[0];
	}
	static void vkQueueSubmit(VkQueue queue, VkSubmitInfo info, int fence) throws VulkanCodeException{
		check(VK10.vkQueueSubmit(queue, info, fence), "vkQueueSubmit");
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
		return new Swapchain(ptr[0], device, createInfo);
	}
	static void vkGetSwapchainImagesKHR(Device device, Swapchain swapchain, IntBuffer pSwapchainImageCount, LongBuffer pSwapchainImages) throws VulkanCodeException{
		check(KHRSwapchain.vkGetSwapchainImagesKHR(device.value, swapchain.handle, pSwapchainImageCount, pSwapchainImages), "vkGetSwapchainImagesKHR");
	}
	static ImageView vkCreateImageView(Device device, VkImageViewCreateInfo info) throws VulkanCodeException{
		var ptr = new long[1];
		check(VK10.vkCreateImageView(device.value, info, null, ptr), "vkCreateImageView");
		return new ImageView(ptr[0], device);
	}
	static long vkCreateCommandPool(Device device, VkCommandPoolCreateInfo info) throws VulkanCodeException{
		var ptr = new long[1];
		check(VK10.vkCreateCommandPool(device.value, info, null, ptr), "vkCreateCommandPool");
		var res = ptr[0];
		return res;
	}
	static VulkanSemaphore vkCreateSemaphore(Device device, VkSemaphoreCreateInfo info) throws VulkanCodeException{
		var ptr = new long[1];
		check(VK10.vkCreateSemaphore(device.value, info, null, ptr), "vkCreateSemaphore");
		return new VulkanSemaphore(ptr[0], device);
	}
	static void vkBeginCommandBuffer(VkCommandBuffer commandBuffer, VkCommandBufferBeginInfo info) throws VulkanCodeException{
		check(VK10.vkBeginCommandBuffer(commandBuffer, info), "vkBeginCommandBuffer");
	}
	static void vkEndCommandBuffer(VkCommandBuffer commandBuffer) throws VulkanCodeException{
		check(VK10.vkEndCommandBuffer(commandBuffer), "vkEndCommandBuffer");
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
		return new RenderPass(res[0], device);
	}
	static FrameBuffer vkCreateFramebuffer(Device device, VkFramebufferCreateInfo info) throws VulkanCodeException{
		var ref = new long[1];
		check(VK10.vkCreateFramebuffer(device.value, info, null, ref), "vkCreateFramebuffer");
		return new FrameBuffer(ref[0], device);
	}
}
