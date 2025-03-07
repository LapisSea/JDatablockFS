package com.lapissea.dfs.tools.newlogger.display.vk;

import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.tools.newlogger.display.VUtils;
import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VKPresentMode;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkAttachmentLoadOp;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkAttachmentStoreOp;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkImageLayout;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkPipelineBindPoint;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkQueueCapability;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkSampleCountFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.DebugLoggerEXT;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Device;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.FrameBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.PhysicalDevice;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.QueueFamilyProps;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.RenderPass;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Surface;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Swapchain;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VulkanQueue;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.dfs.utils.iterableplus.Match;
import com.lapissea.glfw.GlfwWindow;
import com.lapissea.util.ConsoleColors;
import com.lapissea.util.TextUtil;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.Pointer;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkExtent2D;
import org.lwjgl.vulkan.VkExtent3D;
import org.lwjgl.vulkan.VkFramebufferCreateInfo;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkLayerProperties;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.lwjgl.glfw.GLFWVulkan.glfwGetRequiredInstanceExtensions;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.VK_MAKE_API_VERSION;

public class VulkanCore implements AutoCloseable{
	
	private static final boolean VK_DEBUG = Configuration.DEBUG.get(false);
	
	public static void preload(){
		try{
			validateExtensionsLayers(List.of(), List.of());
		}catch(VulkanCodeException e){
			e.printStackTrace();
		}
	}
	
	static{
		TextUtil.CUSTOM_TO_STRINGS.register(VkExtent2D.class, e -> e.width() + "x" + e.height());
		TextUtil.CUSTOM_TO_STRINGS.register(VkExtent3D.class, e -> e.width() + "x" + e.height() + "x" + e.depth());
		TextUtil.CUSTOM_TO_STRINGS.register(Pointer.class, VUtils::vkObjToString);
	}
	
	public static final int API_VERSION_MAJOR = 1;
	public static final int API_VERSION_MINOR = 1;
	
	
	private final String         name;
	private final DebugLoggerEXT debugLog;
	
	private final VkInstance     instance;
	private final Surface        surface;
	private final PhysicalDevice physicalDevice;
	public final  Device         device;
	
	public final QueueFamilyProps renderQueueFamily;
	
	public Swapchain         swapchain;
	public VulkanQueue       renderQueue;
	public RenderPass        renderPass;
	public List<FrameBuffer> frameBuffers;
	
	public VulkanCore(String name, GlfwWindow window) throws VulkanCodeException{
		this.name = name;
		instance = createInstance();
		debugLog = VK_DEBUG? new DebugLoggerEXT(instance, this::debugLogCallback) : null;
		
		surface = VKCalls.glfwCreateWindowSurface(instance, window.getHandle());
		
		var physicalDevices = new PhysicalDevices(instance, surface);
		physicalDevice = physicalDevices.selectDevice(VkQueueCapability.GRAPHICS, true);
		
		renderQueueFamily = findQueueFamilyBy(VkQueueCapability.GRAPHICS).orElseThrow();
		
		Log.info("Using physical device: {}#green", physicalDevice);
		
		device = physicalDevice.createDevice(renderQueueFamily);
		createSwapchainContext();
	}
	
	public void recreateSwapchainContext() throws VulkanCodeException{
		destroySwapchainContext();
		createSwapchainContext();
	}
	
	private void destroySwapchainContext(){
		try{
			renderQueue.waitIdle();
		}catch(VulkanCodeException e){ e.printStackTrace(); }
		
		for(FrameBuffer frameBuffer : frameBuffers){
			frameBuffer.destroy();
		}
		renderPass.close();
		renderQueue.destroy();
		swapchain.destroy();
	}
	private void createSwapchainContext() throws VulkanCodeException{
		swapchain = device.createSwapchain(surface, VKPresentMode.IMMEDIATE);
		
		renderQueue = new VulkanQueue(device, swapchain, renderQueueFamily, 0);
		
		renderPass = createRenderPass();
		frameBuffers = createFrameBuffers();
	}
	
	private List<FrameBuffer> createFrameBuffers() throws VulkanCodeException{
		try(var stack = MemoryStack.stackPush()){
			var viewRef = stack.mallocLong(1);
			var info = VkFramebufferCreateInfo.calloc(stack)
			                                  .sType$Default()
			                                  .renderPass(renderPass.handle)
			                                  .pAttachments(viewRef)
			                                  .width(swapchain.extent.width)
			                                  .height(swapchain.extent.height)
			                                  .layers(1);
			
			var views = swapchain.imageViews;
			var fbs   = new ArrayList<FrameBuffer>(views.size());
			for(var view : views){
				viewRef.put(0, view.handle);
				fbs.add(VKCalls.vkCreateFramebuffer(device, info));
			}
			return List.copyOf(fbs);
		}
	}
	
	private RenderPass createRenderPass() throws VulkanCodeException{
		
		var attachment = new RenderPass.AttachmentInfo(
			swapchain.format,
			VkSampleCountFlag.N1,
			VkAttachmentLoadOp.CLEAR, VkAttachmentStoreOp.STORE,
			VkImageLayout.UNDEFINED, VkImageLayout.PRESENT_SRC_KHR
		);
		
		var subpass = new RenderPass.SubpassInfo(
			VkPipelineBindPoint.GRAPHICS,
			List.of(),
			List.of(new RenderPass.AttachmentReference(0, VkImageLayout.PRESENT_SRC_KHR)),
			List.of(),
			null,
			new int[0]
		);
		
		return device.buildRenderPass().attachment(attachment).subpass(subpass).build();
	}
	
	public Optional<QueueFamilyProps> findQueueFamilyBy(VkQueueCapability capability){
		return Iters.from(physicalDevice.families).firstMatching(e -> e.capabilities.contains(capability));
	}
	
	private static final Set<String> WHITELISTED_ERROR_IDS = Set.of("VUID-VkAttachmentReference-layout-03077");
	
	private synchronized boolean debugLogCallback(DebugLoggerEXT.Severity severity, EnumSet<DebugLoggerEXT.Type> messageTypes, String message, String messageIDName){
		var severityS = Optional.ofNullable(severity).map(e -> e.color + e.name()).orElse(ConsoleColors.RED_BRIGHT + "UNKNOWN") + ConsoleColors.RESET;
		var type      = Iters.from(messageTypes).joinAsOptionalStr(", ").orElse("UNKNOWN");
		
		var msgFinal = message.replace("] Object ", "]\n  Object ")
		                      .replace("; Object ", ";\n  Object ")
		                      .replace("; | MessageID", ";\n| MessageID");
		if(msgFinal.contains("\n")){
			msgFinal = "\n" + msgFinal;
		}
		msgFinal = Log.fmt("[{}#cyan, {}] [{}#blue]: {}", type, severityS, messageIDName, msgFinal);
		
		if(severity == DebugLoggerEXT.Severity.ERROR){
			new RuntimeException(msgFinal).printStackTrace();
			if(!WHITELISTED_ERROR_IDS.contains(messageIDName)){
				System.exit(1);
				return true;
			}
		}else{
			Log.log(msgFinal);
		}
		return false;
	}
	
	private VkInstance createInstance() throws VulkanCodeException{
		try(var stack = MemoryStack.stackPush()){
			
			List<String> layerNames          = new ArrayList<>();
			List<String> extraExtensionNames = new ArrayList<>();
			
			if(VK_DEBUG){
				if(getAvailableLayerNames().contains("VK_LAYER_KHRONOS_validation")){
					layerNames.add("VK_LAYER_KHRONOS_validation");
				}else{
					Log.warn("Could not find VK_LAYER_KHRONOS_validation layer! Make sure SDK is installed");
				}
				extraExtensionNames.add(VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
			}
			
			var requiredExtensions = VUtils.UTF8ArrayToJava(glfwGetRequiredInstanceExtensions());
			if(requiredExtensions == null){
				throw new IllegalStateException("glfwGetRequiredInstanceExtensions failed to find the platform surface extensions.");
			}
			
			var extensionNames = Iters.concat(requiredExtensions, extraExtensionNames).distinct().toList();
			
			if(VK_DEBUG){
				validateExtensionsLayers(extensionNames, layerNames);
			}
			
			var appInfo = VkApplicationInfo.calloc(stack).sType$Default()
			                               .apiVersion(VK_MAKE_API_VERSION(0, API_VERSION_MAJOR, API_VERSION_MINOR, 0))
			                               .pApplicationName(stack.UTF8(name));
			
			var info = VkInstanceCreateInfo.calloc(stack).sType$Default()
			                               .pApplicationInfo(appInfo)
			                               .ppEnabledLayerNames(VUtils.UTF8ArrayOnStack(stack, layerNames))
			                               .ppEnabledExtensionNames(VUtils.UTF8ArrayOnStack(stack, extensionNames));
			
			return VKCalls.vkCreateInstance(stack, info);
		}
	}
	
	private static void validateExtensionsLayers(List<String> extensions, List<String> layerNames) throws VulkanCodeException{
		
		var availableExtensions = getAvailableExtensionNames(null);
		
		if(Iters.from(extensions).filterNot(availableExtensions::contains).joinAsOptionalStrM("\n") instanceof Match.Some(var missing)){
			throw new IllegalStateException("Missing required extension: " + missing + "\n" +
			                                "  Available extensions: " + availableExtensions);
		}
		
		var availableLayers = getAvailableLayerNames();
		
		if(Iters.from(layerNames).filterNot(availableLayers::contains).joinAsOptionalStrM("\n") instanceof Match.Some(var missing)){
			throw new IllegalStateException("Missing required layers: " + missing + "\n" +
			                                "  Available layers: " + availableLayers);
		}
		
	}
	
	private static Set<String> getAvailableExtensionNames(String layerName) throws VulkanCodeException{
		try(var stack = MemoryStack.stackPush()){
			var instance_extensions = getAvailableExtensions(stack, layerName);
			return Iters.from(instance_extensions).map(VkExtensionProperties::extensionNameString).toSet();
		}
	}
	private static VkExtensionProperties.Buffer getAvailableExtensions(MemoryStack stack, String layerName) throws VulkanCodeException{
		var countB = stack.mallocInt(1);
		VKCalls.vkEnumerateInstanceExtensionProperties(layerName, countB, null);
		
		var instance_extensions = VkExtensionProperties.malloc(countB.get(0), stack);
		VKCalls.vkEnumerateInstanceExtensionProperties(layerName, countB, instance_extensions);
		return instance_extensions;
	}
	
	private static Set<String> getAvailableLayerNames() throws VulkanCodeException{
		try(var stack = MemoryStack.stackPush()){
			var res = getAvailableLayers(stack);
			return Iters.from(res).map(VkLayerProperties::layerNameString).toSet();
		}
	}
	private static VkLayerProperties.Buffer getAvailableLayers(MemoryStack stack) throws VulkanCodeException{
		var countB = stack.mallocInt(1);
		VKCalls.vkEnumerateInstanceLayerProperties(countB, null);
		
		var res = VkLayerProperties.malloc(countB.get(0), stack);
		VKCalls.vkEnumerateInstanceLayerProperties(countB, res);
		return res;
	}
	
	@Override
	public void close(){
		destroySwapchainContext();
		
		device.destroy();
		surface.destroy();
		if(debugLog != null) debugLog.destroy();
		VK10.vkDestroyInstance(instance, null);
	}
}
