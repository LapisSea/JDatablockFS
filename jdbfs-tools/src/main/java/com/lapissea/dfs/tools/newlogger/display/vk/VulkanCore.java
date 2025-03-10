package com.lapissea.dfs.tools.newlogger.display.vk;

import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.tools.newlogger.display.ShaderCompiler;
import com.lapissea.dfs.tools.newlogger.display.ShaderType;
import com.lapissea.dfs.tools.newlogger.display.VUtils;
import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VKPresentMode;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkAttachmentLoadOp;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkAttachmentStoreOp;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkBufferUsageFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkCullModeFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkFrontFace;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkImageLayout;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkMemoryPropertyFlags;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkPipelineBindPoint;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkPolygonMode;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkQueueCapability;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkSampleCountFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.CommandPool;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.DebugLoggerEXT;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Device;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.DeviceMemory;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.FrameBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.PhysicalDevice;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Pipeline;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.QueueFamilyProps;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Rect2D;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.RenderPass;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.ShaderModule;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Surface;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Swapchain;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VulkanQueue;
import com.lapissea.dfs.utils.iterableplus.IterablePP;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.dfs.utils.iterableplus.Match;
import com.lapissea.glfw.GlfwWindow;
import com.lapissea.util.ConsoleColors;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.Pointer;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkExtent2D;
import org.lwjgl.vulkan.VkExtent3D;
import org.lwjgl.vulkan.VkFramebufferCreateInfo;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkLayerProperties;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

import java.nio.ByteBuffer;
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
	public Pipeline          pipeline;
	
	private final ShaderModuleSet testShaderModules = new ShaderModuleSet(this, "test", ShaderType.VERTEX, ShaderType.FRAGMENT);
	
	private final CommandPool   transferPool;
	private final CommandBuffer transferBuffer;
	private final VulkanQueue   transferQueue;
	
	
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
		
		
		var transferFamily = findQueueFamilyBy(VkQueueCapability.TRANSFER).orElseThrow();
		transferPool = device.createCommandPool(transferFamily, CommandPool.Type.NORMAL);
		transferBuffer = transferPool.createCommandBuffer();
		transferQueue = new VulkanQueue(device, null, transferFamily, 1);
		createSwapchainContext();
	}
	
	public BufferAndMemory createBuffer(long size, Set<VkBufferUsageFlag> usageFlags, Set<VkMemoryPropertyFlags> memoryFlags) throws VulkanCodeException{
		VkBuffer     buffer = null;
		DeviceMemory memory = null;
		try(var stack = MemoryStack.stackPush()){
			var info = VkBufferCreateInfo.calloc(stack)
			                             .sType$Default()
			                             .size(size)
			                             .usage(Iters.from(usageFlags).mapToInt(b -> b.bit).reduce(0, (a, b) -> a|b))
			                             .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);
			
			buffer = VKCalls.vkCreateBuffer(device, info);
			
			var requirement = buffer.getRequirements();
			
			int memoryTypeIndex = getMemoryTypeIndex(requirement.memoryTypeBits(), memoryFlags);
			
			var pAllocateInfo = VkMemoryAllocateInfo.calloc(stack)
			                                        .sType$Default()
			                                        .allocationSize(requirement.size())
			                                        .memoryTypeIndex(memoryTypeIndex);
			
			memory = VKCalls.vkAllocateMemory(device, pAllocateInfo);
			
			VKCalls.vkBindBufferMemory(buffer, memory, 0);
			
			return new BufferAndMemory(buffer, memory, requirement.size());
		}catch(Throwable e){
			if(buffer != null) buffer.destroy();
			if(memory != null) memory.destroy();
			throw e;
		}
	}
	
	private int getMemoryTypeIndex(int typeBits, Set<VkMemoryPropertyFlags> requiredProperties){
		return Iters.from(physicalDevice.memoryProperties.memoryTypes())
		            .enumerate()
		            .filter(e -> {
			            var typeSupported = UtilL.checkFlag(typeBits, 1<<e.index());
			            var hasMemProps   = e.val().propertyFlags.containsAll(requiredProperties);
			            return typeSupported && hasMemProps;
		            }).map(IterablePP.Idx::index)
		            .findFirst()
		            .orElseThrow(() -> new IllegalStateException("Could not find memory type for: " + typeBits + " & " + requiredProperties));
	}
	
	public BufferAndMemory createVertexBuffer(ByteBuffer data) throws VulkanCodeException{
		var size = data.remaining();
		
		try(var stagingVb = createBuffer(
			size,
			EnumSet.of(VkBufferUsageFlag.TRANSFER_SRC),
			EnumSet.of(VkMemoryPropertyFlags.HOST_VISIBLE, VkMemoryPropertyFlags.HOST_COHERENT)
		)){
			try(var mem = VKCalls.vkMapMemory(stagingVb.memory, 0, stagingVb.allocationSize, 0)){
				mem.put(data);
			}
			
			var vb = createBuffer(
				size,
				EnumSet.of(VkBufferUsageFlag.STORAGE_BUFFER, VkBufferUsageFlag.TRANSFER_DST),
				EnumSet.of(VkMemoryPropertyFlags.DEVICE_LOCAL)
			);
			stagingVb.copyTo(transferBuffer, transferQueue, vb);
			return vb;
		}
	}
	
	public ShaderModule createShaderModule(ByteBuffer spirv, ShaderType type) throws VulkanCodeException{
		var device = this.device;
		try(var stack = MemoryStack.stackPush()){
			
			var pCreateInfo = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spirv);
			
			return VKCalls.vkCreateShaderModule(device, type.vkFlag, pCreateInfo);
		}
	}
	public static ByteBuffer sourceToSpirv(String name, ShaderType type){
		var path = "/shaders/" + name + "." + type.extension;
		
		ByteBuffer spirv;
		try{
			spirv = ShaderCompiler.glslToSpirv(path, type.vkFlag, VK_MAKE_API_VERSION(0, API_VERSION_MAJOR, API_VERSION_MINOR, 0));
		}catch(Throwable e){
			throw new RuntimeException("Failed to compile shader: " + name + " - " + type, e);
		}
		return spirv;
	}
	
	public void recreateSwapchainContext() throws VulkanCodeException{
		destroySwapchainContext();
		createSwapchainContext();
	}
	
	private void createSwapchainContext() throws VulkanCodeException{
		swapchain = device.createSwapchain(surface, VKPresentMode.IMMEDIATE);
		
		renderQueue = new VulkanQueue(device, swapchain, renderQueueFamily, 0);
		
		renderPass = createRenderPass();
		frameBuffers = createFrameBuffers();
		
		pipeline = createPipeline();
	}
	private void destroySwapchainContext(){
		try{
			renderQueue.waitIdle();
		}catch(VulkanCodeException e){ e.printStackTrace(); }
		
		pipeline.destroy();
		
		for(FrameBuffer frameBuffer : frameBuffers){
			frameBuffer.destroy();
		}
		renderPass.close();
		renderQueue.destroy();
		swapchain.destroy();
	}
	
	private Pipeline createPipeline() throws VulkanCodeException{
		var area = new Rect2D(swapchain.extent);
		return device.createPipeline(
			renderPass, 0, testShaderModules, area, area,
			VkPolygonMode.FILL, VkCullModeFlag.FRONT, VkFrontFace.CLOCKWISE, VkSampleCountFlag.N1
		);
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
			List.of(new RenderPass.AttachmentReference(0, VkImageLayout.COLOR_ATTACHMENT_OPTIMAL)),
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
		                      .replace("; | MessageID", ";\n| MessageID")
		                      .replace("] | MessageID", "]\n| MessageID");
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
		
		try{
			transferQueue.waitIdle();
		}catch(VulkanCodeException e){ e.printStackTrace(); }
		transferQueue.destroy();
		transferBuffer.destroy();
		transferPool.destroy();
		
		testShaderModules.destroy();
		device.destroy();
		surface.destroy();
		if(debugLog != null) debugLog.destroy();
		VK10.vkDestroyInstance(instance, null);
	}
}
