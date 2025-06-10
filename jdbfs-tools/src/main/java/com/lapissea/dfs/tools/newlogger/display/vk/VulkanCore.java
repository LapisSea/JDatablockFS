package com.lapissea.dfs.tools.newlogger.display.vk;

import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.tools.newlogger.display.ShaderCompiler;
import com.lapissea.dfs.tools.newlogger.display.ShaderType;
import com.lapissea.dfs.tools.newlogger.display.TextureRegistry;
import com.lapissea.dfs.tools.newlogger.display.VUtils;
import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VKPresentMode;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkAttachmentLoadOp;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkAttachmentStoreOp;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkBufferUsageFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkColorSpaceKHR;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkDescriptorPoolCreateFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkDescriptorType;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkFilter;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkFormat;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkImageAspectFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkImageLayout;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkImageUsageFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkImageViewType;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkMemoryPropertyFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkPipelineBindPoint;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkQueueFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkSampleCountFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkSamplerAddressMode;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkShaderStageFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.DebugLoggerEXT;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Descriptor;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Device;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.FormatColor;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.PhysicalDevice;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.QueueFamilyProps;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Rect2D;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.RenderPass;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.ShaderModule;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkDescriptorPool;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkDescriptorSetLayout;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkSampler;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VulkanQueue;
import com.lapissea.dfs.utils.iterableplus.IterablePP;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.dfs.utils.iterableplus.Match;
import com.lapissea.glfw.GlfwWindow;
import com.lapissea.util.ConsoleColors;
import com.lapissea.util.TextUtil;
import com.lapissea.util.function.UnsafeConsumer;
import org.joml.Matrix4f;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Pointer;
import org.lwjgl.system.Struct;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkDrawIndirectCommand;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkExtent2D;
import org.lwjgl.vulkan.VkExtent3D;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkLayerProperties;
import org.lwjgl.vulkan.VkOffset2D;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static org.lwjgl.glfw.GLFWVulkan.glfwGetRequiredInstanceExtensions;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.VK_MAKE_API_VERSION;

public class VulkanCore implements AutoCloseable{
	
	public static class GlobalUniform extends Struct<GlobalUniform>{
		private static final int SIZEOF;
		private static final int MAT;
		
		static{
			var layout = __struct(
				__member(4*4*Float.BYTES)
			);
			
			SIZEOF = layout.getSize();
			MAT = layout.offsetof(0);
		}
		
		public void mat(Matrix4f mat){
			mat.getToAddress(address + MAT);
		}
		
		public GlobalUniform(ByteBuffer buff){ super(MemoryUtil.memAddress(buff), buff); }
		protected GlobalUniform(long address){ super(address, null); }
		@Override
		protected GlobalUniform create(long address, ByteBuffer container){ return new GlobalUniform(address); }
		@Override
		public int sizeof(){ return SIZEOF; }
	}
	
	
	public static final boolean VK_DEBUG = Configuration.DEBUG.get(false);
	
	public static final int MAX_IN_FLIGHT_FRAMES = 2;
	
	public static final List<FormatColor> PREFERRED_SWAPCHAIN_FORMATS = List.of(
		new FormatColor(VkFormat.R8G8B8A8_UNORM, VkColorSpaceKHR.SRGB_NONLINEAR_KHR)
	);
	
	public static void preload(){
		try{
			validateExtensionsLayers(List.of(), List.of());
			Class.forName(STBImage.class.getName(), true, VulkanCore.class.getClassLoader());
		}catch(Throwable e){
			e.printStackTrace();
		}
	}
	
	static{
		TextUtil.CUSTOM_TO_STRINGS.register(VkExtent2D.class, e -> e.width() + "x" + e.height());
		TextUtil.CUSTOM_TO_STRINGS.register(VkExtent3D.class, e -> e.width() + "x" + e.height() + "x" + e.depth());
		TextUtil.CUSTOM_TO_STRINGS.register(Pointer.class, p -> VUtils.vkObjToString(p, true));
		TextUtil.SHORT_TO_STRINGS.register(Pointer.class, p -> VUtils.vkObjToString(p, false));
		TextUtil.CUSTOM_TO_STRINGS.register(VkOffset2D.class, p -> "VkOffset2D{x: " + p.x() + ", y: " + p.y() + "}");
		TextUtil.SHORT_TO_STRINGS.register(VkOffset2D.class, p -> "{x: " + p.x() + ", y: " + p.y() + "}");
		TextUtil.CUSTOM_TO_STRINGS.register(VkRect2D.class, p -> new Rect2D(p).toString());
		TextUtil.SHORT_TO_STRINGS.register(VkRect2D.class, p -> new Rect2D(p).toShortString());
		TextUtil.CUSTOM_TO_STRINGS.register(VulkanResource.class, res -> {
			Map<String, String> data = new LinkedHashMap<>();
			
			TextUtil.mapObjectValues(res, (name, obj) -> {
				if(List.of("handle", "device").contains(name)) return;
				if(obj instanceof String){
					obj = "\"" + ((String)obj).replace("\n", "\\n").replace("\r", "\\r") + '"';
				}
				data.put(name, TextUtil.toString(obj));
			});
			
			return Iters.entries(data)
			            .map(e -> e.getKey() + ": " + e.getValue())
			            .joinAsStr(", ", res.getClass().getSimpleName() + "{", "}");
		});
	}
	
	public static final int API_VERSION_MAJOR = 1;
	public static final int API_VERSION_MINOR = 2;
	
	
	private final String         name;
	private final DebugLoggerEXT debugLog;
	
	public final VkInstance     instance;
	public final PhysicalDevice physicalDevice;
	public final Device         device;
	
	public final QueueFamilyProps renderQueueFamily;
	public final QueueFamilyProps transferQueueFamily;
	
	public final VulkanQueue.SwapSync renderQueue;
	public       RenderPass           renderPass;
	public final TransferBuffers      transferBuffers;
	public final TransferBuffers      transientGraphicsBuffs;
	
	public final VKPresentMode preferredPresentMode;
	
	public final VkDescriptorSetLayout globalUniformLayout;
	
	public final VkDescriptorPool globalDescriptorPool;
	
	public final VkSampler defaultSampler;
	
	public final TextureRegistry textureRegistry = new TextureRegistry(this);
	
	public VulkanCore(String name, VKPresentMode preferredPresentMode) throws VulkanCodeException{
		this.name = name;
		this.preferredPresentMode = preferredPresentMode;
		
		instance = createInstance();
		debugLog = VK_DEBUG? new DebugLoggerEXT(instance, this::debugLogCallback) : null;
		
		var requiredFeatures = Set.of(VkQueueFlag.GRAPHICS, VkQueueFlag.TRANSFER);
		
		var physicalDevices = new PhysicalDevices(instance);
		physicalDevice = physicalDevices.selectDevice(requiredFeatures);
		
		var graphicsFamilies = queueFamiliesBy(VkQueueFlag.GRAPHICS);
		var transferFamilies = queueFamiliesBy(VkQueueFlag.TRANSFER);
		
		renderQueueFamily = graphicsFamilies.getFirst();
		transferQueueFamily = transferFamilies.filter(graphicsFamilies::noneIs).findFirst()
		                                      .orElseGet(transferFamilies::getFirst);
		
		Log.info("Using physical device: {}#green", physicalDevice);
		
		device = physicalDevice.createDevice(Iters.of(renderQueueFamily, transferQueueFamily).distinct().toList(), new File("pipelineCache.bin"));
		
		renderQueue = device.allocateQueue(renderQueueFamily).withSwap();
		
		TransferBuffers transferBuffers, transientGraphicsBuffs;
		try{
			transferBuffers = new TransferBuffers(device.allocateQueue(transferQueueFamily), true);
			transientGraphicsBuffs = new TransferBuffers(device.allocateQueue(renderQueueFamily), true);
		}catch(UnsupportedOperationException e){
			Log.warn("Switching to single queue mode!\n  {}#red", e);
			transferBuffers = transientGraphicsBuffs = new TransferBuffers(renderQueue, false);
		}
		this.transferBuffers = transferBuffers;
		this.transientGraphicsBuffs = transientGraphicsBuffs;
		
		
		globalDescriptorPool = device.createDescriptorPool(1000, VkDescriptorPoolCreateFlag.FREE_DESCRIPTOR_SET);
		
		defaultSampler = device.createSampler(VkFilter.LINEAR, VkFilter.LINEAR, VkSamplerAddressMode.REPEAT);
		
		globalUniformLayout = globalDescriptorPool.createDescriptorSetLayout(List.of(
			new Descriptor.LayoutBinding(0, VkShaderStageFlag.VERTEX, VkDescriptorType.UNIFORM_BUFFER)
		));
		
		
		VkFormat format = chooseFormat();
		renderPass = createRenderPass(device, format);
		
		
		Log.trace("Finished initializing VulkanCore");
	}
	
	private VkFormat chooseFormat() throws VulkanCodeException{
		VkFormat format;
		var      window = new GlfwWindow();
		window.init(i -> i.withVulkan(v -> v.withVersion(VulkanCore.API_VERSION_MAJOR, VulkanCore.API_VERSION_MINOR)).resizeable(true));
		try(var surface = VKCalls.glfwCreateWindowSurface(instance, window.getHandle())){
			format = surface.chooseSwapchainFormat(physicalDevice, PREFERRED_SWAPCHAIN_FORMATS).format;
		}finally{
			window.destroy();
		}
		return format;
	}
	
	public VulkanTexture uploadTexture(int width, int height, ByteBuffer pixels, VkFormat format, int mipLevels) throws VulkanCodeException{
		var usage = Flags.of(VkImageUsageFlag.TRANSFER_DST, VkImageUsageFlag.SAMPLED);
		if(mipLevels>1) usage = usage.and(VkImageUsageFlag.TRANSFER_SRC);
		var image = device.createImage(width, height, format, usage, VkSampleCountFlag.N1, mipLevels);
		
		var memory = image.allocateAndBindRequiredMemory(physicalDevice, VkMemoryPropertyFlag.DEVICE_LOCAL);
		
		image.updateContents(transferBuffers, transientGraphicsBuffs, pixels, mipLevels);
		
		var view = image.createImageView(VkImageViewType.TYPE_2D, image.format, VkImageAspectFlag.COLOR, mipLevels, 1);
		
		return new VulkanTexture(image, memory, view, defaultSampler, false);
	}
	
	public <T extends Struct<T>>
	UniformBuffer<T> allocateUniformBuffer(int size, boolean ssbo, Function<ByteBuffer, T> ctor) throws VulkanCodeException{
		var res = new BackedVkBuffer[MAX_IN_FLIGHT_FRAMES];
		for(int i = 0; i<res.length; i++){
			res[i] = allocateHostBuffer(size, ssbo? VkBufferUsageFlag.STORAGE_BUFFER : VkBufferUsageFlag.UNIFORM_BUFFER);
		}
		return new UniformBuffer<>(List.of(res), ssbo, ctor);
	}
	public IndirectDrawBuffer.PerFrame allocateIndirectBufferPerFrame(int instanceCount) throws VulkanCodeException{
		var res = new IndirectDrawBuffer[MAX_IN_FLIGHT_FRAMES];
		for(int i = 0; i<res.length; i++){
			res[i] = allocateIndirectBuffer(instanceCount);
		}
		return new IndirectDrawBuffer.PerFrame(res);
	}
	public IndirectDrawBuffer allocateIndirectBuffer(int instanceCount) throws VulkanCodeException{
		var buf = allocateHostBuffer((long)instanceCount*VkDrawIndirectCommand.SIZEOF, VkBufferUsageFlag.INDIRECT_BUFFER);
		return new IndirectDrawBuffer(buf);
	}
	
	public BackedVkBuffer allocateStagingBuffer(long size) throws VulkanCodeException{
		return device.allocateStagingBuffer(size);
	}
	public BackedVkBuffer allocateHostBuffer(long size, VkBufferUsageFlag usage) throws VulkanCodeException{
		return device.allocateHostBuffer(size, usage);
	}
	
	public <E extends Throwable> BackedVkBuffer allocateDeviceLocalBuffer(long size, UnsafeConsumer<ByteBuffer, E> populator) throws E, VulkanCodeException{
		return device.allocateDeviceLocalBuffer(transferBuffers, size, populator);
	}
	public BackedVkBuffer allocateBuffer(long size, Flags<VkBufferUsageFlag> usageFlags, Flags<VkMemoryPropertyFlag> memoryFlags) throws VulkanCodeException{
		return device.allocateBuffer(size, usageFlags, memoryFlags);
	}
	
	public ShaderModule createShaderModule(ByteBuffer spirv, ShaderType type) throws VulkanCodeException{
		var device = this.device;
		try(var stack = MemoryStack.stackPush()){
			
			var pCreateInfo = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spirv);
			
			return VKCalls.vkCreateShaderModule(device, type.vkFlag, pCreateInfo);
		}
	}
	
	public ByteBuffer sourceToSpirv(String name, ShaderType type){
		var path = "shaders/" + name + "." + type.extension;
		
		ByteBuffer spirv;
		try{
			spirv = ShaderCompiler.glslToSpirv(
				path, type.vkFlag, VK_MAKE_API_VERSION(0, API_VERSION_MAJOR, API_VERSION_MINOR, 0),
				!device.hasArithmeticTypes
			);
		}catch(Throwable e){
			throw new RuntimeException("Failed to compile shader: " + name + " - " + type, e);
		}
		return spirv;
	}
	
	private static RenderPass createRenderPass(Device device, VkFormat colorFormat) throws VulkanCodeException{
		var physicalDevice = device.physicalDevice;
		var mssaAttachment = new RenderPass.AttachmentInfo(
			colorFormat,
			physicalDevice.samples,
			VkAttachmentLoadOp.CLEAR, VkAttachmentStoreOp.STORE,
			VkImageLayout.UNDEFINED, VkImageLayout.COLOR_ATTACHMENT_OPTIMAL
		);
		var presentAttachment = new RenderPass.AttachmentInfo(
			colorFormat,
			VkSampleCountFlag.N1,
			VkAttachmentLoadOp.DONT_CARE, VkAttachmentStoreOp.STORE,
			VkImageLayout.UNDEFINED, VkImageLayout.PRESENT_SRC_KHR
		);
		
		var subpass = new RenderPass.SubpassInfo(
			VkPipelineBindPoint.GRAPHICS,
			List.of(),
			List.of(new RenderPass.AttachmentReference(0, VkImageLayout.COLOR_ATTACHMENT_OPTIMAL)),
			List.of(new RenderPass.AttachmentReference(1, VkImageLayout.COLOR_ATTACHMENT_OPTIMAL)),
			null,
			new int[0]
		);
		
		return device.buildRenderPass().attachment(mssaAttachment).attachment(presentAttachment).subpass(subpass).build();
	}
	
	public IterablePP<QueueFamilyProps> queueFamiliesBy(VkQueueFlag capability){
		return Iters.from(physicalDevice.families).filter(e -> e.capabilities.contains(capability));
	}
	
	private static final Set<String> IGNORE_IDS = new HashSet<>(Set.of("Loader Message"));
	
	private synchronized boolean debugLogCallback(DebugLoggerEXT.Severity severity, EnumSet<DebugLoggerEXT.Type> messageTypes, String message, String messageIDName, long[] handles){
		if(severity == DebugLoggerEXT.Severity.INFO && IGNORE_IDS.contains(messageIDName)){
			return false;
		}
		
		var severityS = Optional.ofNullable(severity).map(e -> e.color + e.name()).orElse(ConsoleColors.RED_BRIGHT + "UNKNOWN") + ConsoleColors.RESET;
		var type      = Iters.from(messageTypes).joinAsOptionalStr(", ").orElse("UNKNOWN");
		
		var msgFinal = message.replace("] Object ", "]\n  Object ")
		                      .replace("; Object ", ";\n  Object ")
		                      .replace("; | MessageID", ";\n| MessageID")
		                      .replace("] | MessageID", "]\n| MessageID");
		if(msgFinal.contains("\n")){
			msgFinal = "\n" + msgFinal;
		}
		msgFinal = Log.fmt("[{#purpleVK-Callback#}] [{}#cyan, {}] [{}#blue]: {}", type, severityS, messageIDName, msgFinal);
		
		if(severity == DebugLoggerEXT.Severity.ERROR){
			var err = new RuntimeException(msgFinal);
			err.setStackTrace(
				Iters.concat1N(
					err.getStackTrace()[0],
					Iters.from(err.getStackTrace())
					     .dropWhile(e -> !e.isNativeMethod())
					     .skip(1)
					     .dropWhile(e -> e.getMethodName().startsWith("nvk") && e.getClassName().startsWith(VK10.class.getPackageName()))
				).toArray(StackTraceElement[]::new)
			);
			if(messageIDName.equals("VUID-vkDestroyDevice-device-05137")){
				for(var ctorInit : Iters.ofLongs(handles).box().map(device.debugVkObjects::get).nonNulls()){
					err.addSuppressed(ctorInit);
				}
			}
			throw err;
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
			
			GlfwWindow.initGLFW();
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
	public void close() throws VulkanCodeException{
		device.waitIdle();
		
		for(var queue : List.of(transferBuffers, transientGraphicsBuffs, renderQueue)){
			queue.destroy();
		}
		
		globalUniformLayout.destroy();
		globalDescriptorPool.destroy();
		
		defaultSampler.destroy();
		
		renderPass.destroy();
		device.destroy();
		
		if(debugLog != null) debugLog.destroy();
		VK10.vkDestroyInstance(instance, null);
	}
	
	private final List<VulkanQueue.SwapSync.PresentFrame> present = new ArrayList<>();
	
	public void pushSwap(VulkanQueue.SwapSync.PresentFrame presentFrame){
		present.add(presentFrame);
	}
	public boolean executeSwaps() throws VulkanCodeException{
		var swap = renderQueue.present(present);
		present.clear();
		return swap;
	}
}
