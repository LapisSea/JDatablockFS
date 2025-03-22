package com.lapissea.dfs.tools.newlogger.display.vk;

import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.tools.newlogger.display.ShaderCompiler;
import com.lapissea.dfs.tools.newlogger.display.ShaderType;
import com.lapissea.dfs.tools.newlogger.display.VUtils;
import com.lapissea.dfs.tools.newlogger.display.VulkanCodeException;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VKPresentMode;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkAccessFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkAttachmentLoadOp;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkAttachmentStoreOp;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkBufferUsageFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkColorSpaceKHR;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkDescriptorType;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkFilter;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkFormat;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkImageAspectFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkImageLayout;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkImageUsageFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkImageViewType;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkMemoryPropertyFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkPipelineBindPoint;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkPipelineStageFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkQueueFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkSampleCountFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkSamplerAddressMode;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkShaderStageFlag;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkSharingMode;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.DebugLoggerEXT;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.DescriptorSetLayoutBinding;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Device;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.FormatColor;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.FrameBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.MemoryBarrier;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.PhysicalDevice;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.QueueFamilyProps;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Rect2D;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.RenderPass;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.ShaderModule;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Surface;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Swapchain;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkDeviceMemory;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VkImage;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.VulkanQueue;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.dfs.utils.iterableplus.Match;
import com.lapissea.glfw.GlfwWindow;
import com.lapissea.util.ConsoleColors;
import com.lapissea.util.TextUtil;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.Pointer;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkExtent2D;
import org.lwjgl.vulkan.VkExtent3D;
import org.lwjgl.vulkan.VkFramebufferCreateInfo;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkLayerProperties;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

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
		STBImage.stbi_failure_reason();
	}
	
	static{
		TextUtil.CUSTOM_TO_STRINGS.register(VkExtent2D.class, e -> e.width() + "x" + e.height());
		TextUtil.CUSTOM_TO_STRINGS.register(VkExtent3D.class, e -> e.width() + "x" + e.height() + "x" + e.depth());
		TextUtil.CUSTOM_TO_STRINGS.register(Pointer.class, VUtils::vkObjToString);
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
	public static final int API_VERSION_MINOR = 1;
	
	
	private final String         name;
	private final DebugLoggerEXT debugLog;
	
	private final VkInstance     instance;
	private final Surface        surface;
	private final PhysicalDevice physicalDevice;
	public final  Device         device;
	
	public final QueueFamilyProps renderQueueFamily;
	
	public Swapchain           swapchain;
	public List<VulkanTexture> mssaImages;
	public VulkanQueue         renderQueue;
	public RenderPass          renderPass;
	public List<FrameBuffer>   frameBuffers;
	
	private final ShaderModuleSet testShaderModules = new ShaderModuleSet(this, "test", ShaderType.VERTEX, ShaderType.FRAGMENT);
	
	private final TransferBuffers transferBuffers;
	
	public VulkanCore(String name, GlfwWindow window) throws VulkanCodeException{
		this.name = name;
		
		instance = createInstance();
		debugLog = VK_DEBUG? new DebugLoggerEXT(instance, this::debugLogCallback) : null;
		
		surface = VKCalls.glfwCreateWindowSurface(instance, window.getHandle());
		
		var physicalDevices = new PhysicalDevices(instance, surface);
		physicalDevice = physicalDevices.selectDevice(VkQueueFlag.GRAPHICS, true);
		
		renderQueueFamily = findQueueFamilyBy(VkQueueFlag.GRAPHICS).orElseThrow();
		
		Log.info("Using physical device: {}#green", physicalDevice);
		
		device = physicalDevice.createDevice(renderQueueFamily);
		
		
		var transferFamily = findQueueFamilyBy(VkQueueFlag.TRANSFER).orElseThrow();
		
		var transferQueue = new VulkanQueue(device, null, transferFamily, 1);
		transferBuffers = new TransferBuffers(transferQueue);
		
		createSwapchainContext();
	}
	
	public VulkanTexture uploadTexture(int width, int height, ByteBuffer pixels, VkFormat format, int mipLevels) throws VulkanCodeException{
		var usage = Flags.of(VkImageUsageFlag.TRANSFER_DST, VkImageUsageFlag.SAMPLED);
		if(mipLevels>1) usage = usage.and(VkImageUsageFlag.TRANSFER_SRC);
		var image = device.createImage(width, height, format, usage, VkSampleCountFlag.N1, mipLevels);
		
		var memory = image.allocateAndBindRequiredMemory(physicalDevice, Flags.of(VkMemoryPropertyFlag.DEVICE_LOCAL));
		
		imageUpdate(image, pixels, mipLevels);
		
		var view = image.createImageView(VkImageViewType.TYPE_2D, image.format, Flags.of(VkImageAspectFlag.COLOR), mipLevels, 1);
		
		var sampler = image.createSampler(VkFilter.LINEAR, VkFilter.LINEAR, VkSamplerAddressMode.REPEAT);
		
		return new VulkanTexture(image, memory, view, sampler);
	}
	
	public void imageUpdate(VkImage image, ByteBuffer pixels, int mipLevels) throws VulkanCodeException{
		int size = VUtils.getBytesPerPixel(image.format)*image.extent.depth*image.extent.width*image.extent.height;
		
		if(pixels.remaining() != size) throw new AssertionError(pixels.remaining() + " != " + size);
		try(var imgMem = allocateStagingBuffer(size)){
			imgMem.update(b -> b.put(pixels));
			
			image.transitionLayout(transferBuffers, VkImageLayout.UNDEFINED, VkImageLayout.TRANSFER_DST_OPTIMAL, mipLevels);
			image.copyBufferToImage(transferBuffers, imgMem.buffer);
			if(mipLevels>1){
				generateMips(image, mipLevels);
			}else{
				image.transitionLayout(transferBuffers, VkImageLayout.TRANSFER_DST_OPTIMAL, VkImageLayout.SHADER_READ_ONLY_OPTIMAL, mipLevels);
			}
		}
	}
	
	private void generateMips(VkImage image, int mipLevels) throws VulkanCodeException{
		transferBuffers.syncAction(buf -> {
			try(var stack = MemoryStack.stackPush()){
				var subresource = VkImageSubresourceRange.malloc(stack);
				subresource.aspectMask(VkImageAspectFlag.COLOR.bit)
				           .baseArrayLayer(0)
				           .layerCount(1)
				           .levelCount(1);
				
				
				var blits = VkImageBlit.calloc(1, stack);
				var blit  = blits.get(0);
				
				int mipWidth  = image.extent.width;
				int mipHeight = image.extent.height;
				
				for(int i0 = 1; i0<mipLevels; i0++){
					var i = i0;
					subresource.baseMipLevel(i - 1);
					var imgBar = new MemoryBarrier.BarImage(
						VkAccessFlag.TRANSFER_WRITE,
						VkAccessFlag.TRANSFER_READ,
						VkImageLayout.TRANSFER_DST_OPTIMAL,
						VkImageLayout.TRANSFER_SRC_OPTIMAL,
						image,
						subresource
					);
					buf.pipelineBarrier(VkPipelineStageFlag.TRANSFER, VkPipelineStageFlag.TRANSFER, 0, List.of(imgBar));
					
					blit.srcOffsets(0).set(0, 0, 0);
					blit.srcOffsets(1).set(mipWidth, mipHeight, 1);
					blit.srcSubresource(r -> r.aspectMask(VkImageAspectFlag.COLOR.bit)
					                          .mipLevel(i - 1)
					                          .baseArrayLayer(0)
					                          .layerCount(1));
					
					mipWidth = Math.max(1, mipWidth/2);
					mipHeight = Math.max(1, mipHeight/2);
					
					blit.dstOffsets(0).set(0, 0, 0);
					blit.dstOffsets(1).set(mipWidth, mipHeight, 1);
					blit.dstSubresource(r -> r.aspectMask(VkImageAspectFlag.COLOR.bit)
					                          .mipLevel(i)
					                          .baseArrayLayer(0)
					                          .layerCount(1));
					
					buf.blitImage(image, VkImageLayout.TRANSFER_SRC_OPTIMAL,
					              image, VkImageLayout.TRANSFER_DST_OPTIMAL,
					              blits, VkFilter.LINEAR);
					
					buf.pipelineBarrier(VkPipelineStageFlag.TRANSFER, VkPipelineStageFlag.FRAGMENT_SHADER, 0, List.of(
						new MemoryBarrier.BarImage(
							imgBar,
							VkAccessFlag.SHADER_READ,
							VkImageLayout.SHADER_READ_ONLY_OPTIMAL
						)
					));
				}
				subresource.baseMipLevel(mipLevels - 1);
				buf.pipelineBarrier(VkPipelineStageFlag.TRANSFER, VkPipelineStageFlag.FRAGMENT_SHADER, 0, List.of(new MemoryBarrier.BarImage(
					VkAccessFlag.TRANSFER_WRITE,
					VkAccessFlag.SHADER_READ,
					VkImageLayout.TRANSFER_DST_OPTIMAL,
					VkImageLayout.SHADER_READ_ONLY_OPTIMAL,
					image,
					subresource
				)));
			}
			
		});
	}
	
	public List<BufferAndMemory> allocateUniformBuffers(int size) throws VulkanCodeException{
		var res = new BufferAndMemory[swapchain.images.size()];
		for(int i = 0; i<res.length; i++){
			res[i] = allocateBuffer(
				size,
				Flags.of(VkBufferUsageFlag.UNIFORM_BUFFER),
				Flags.of(VkMemoryPropertyFlag.HOST_VISIBLE, VkMemoryPropertyFlag.HOST_COHERENT)
			);
		}
		return List.of(res);
	}
	
	public BufferAndMemory allocateStagingBuffer(long size) throws VulkanCodeException{
		return allocateBuffer(size, Flags.of(VkBufferUsageFlag.TRANSFER_SRC),
		                      Flags.of(VkMemoryPropertyFlag.HOST_VISIBLE, VkMemoryPropertyFlag.HOST_COHERENT));
	}
	public BufferAndMemory allocateBuffer(long size, Flags<VkBufferUsageFlag> usageFlags, Flags<VkMemoryPropertyFlag> memoryFlags) throws VulkanCodeException{
		VkBuffer       buffer = null;
		VkDeviceMemory memory = null;
		try{
			buffer = device.createBuffer(size, usageFlags, VkSharingMode.EXCLUSIVE);
			
			memory = buffer.allocateAndBindRequiredMemory(physicalDevice, memoryFlags);
			
			return new BufferAndMemory(buffer, memory);
		}catch(Throwable e){
			if(memory != null) memory.destroy();
			if(buffer != null) buffer.destroy();
			throw e;
		}
	}
	
	public BufferAndMemory createVertexBuffer(int size, Consumer<ByteBuffer> populator) throws VulkanCodeException{
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
	
	public ShaderModule createShaderModule(ByteBuffer spirv, ShaderType type) throws VulkanCodeException{
		var device = this.device;
		try(var stack = MemoryStack.stackPush()){
			
			var pCreateInfo = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spirv);
			
			return VKCalls.vkCreateShaderModule(device, type.vkFlag, pCreateInfo);
		}
	}
	public static ByteBuffer sourceToSpirv(String name, ShaderType type){
		var path = "shaders/" + name + "." + type.extension;
		
		ByteBuffer spirv;
		try{
			spirv = ShaderCompiler.glslToSpirv(path, type.vkFlag, VK_MAKE_API_VERSION(0, API_VERSION_MAJOR, API_VERSION_MINOR, 0));
		}catch(Throwable e){
			throw new RuntimeException("Failed to compile shader: " + name + " - " + type, e);
		}
		return spirv;
	}
	
	public void recreateSwapchainContext() throws VulkanCodeException{
		destroySwapchainContext(false);
		createSwapchainContext();
	}
	
	private void createSwapchainContext() throws VulkanCodeException{
		var oldSwapchain = swapchain;
		swapchain = device.createSwapchain(
			oldSwapchain, surface, VKPresentMode.IMMEDIATE,
			List.of(
				new FormatColor(VkFormat.R8G8B8A8_UNORM, VkColorSpaceKHR.SRGB_NONLINEAR_KHR)
			)
		);
		if(oldSwapchain != null) oldSwapchain.destroy();
		
		var images = new ArrayList<VulkanTexture>(swapchain.images.size());
		for(int i = 0; i<swapchain.images.size(); i++){
			var image = device.createImage(swapchain.extent.width, swapchain.extent.height, swapchain.formatColor.format,
			                               Flags.of(VkImageUsageFlag.TRANSIENT_ATTACHMENT, VkImageUsageFlag.COLOR_ATTACHMENT),
			                               physicalDevice.samples, 1);
			
			var memory = image.allocateAndBindRequiredMemory(physicalDevice, Flags.of(VkMemoryPropertyFlag.DEVICE_LOCAL));
			
			var view = image.createImageView(VkImageViewType.TYPE_2D, image.format, Flags.of(VkImageAspectFlag.COLOR));
			
			images.add(new VulkanTexture(image, memory, view, null));
		}
		mssaImages = images;
		
		renderQueue = new VulkanQueue(device, swapchain, renderQueueFamily, 0);
		
		renderPass = createRenderPass();
		frameBuffers = createFrameBuffers();
	}
	
	private void destroySwapchainContext(boolean destroySwapchain){
		try{
			renderQueue.waitIdle();
		}catch(VulkanCodeException e){ e.printStackTrace(); }
		
		for(FrameBuffer frameBuffer : frameBuffers){
			frameBuffer.destroy();
		}
		renderPass.close();
		renderQueue.destroy();
		mssaImages.forEach(VulkanTexture::destroy);
		if(destroySwapchain) swapchain.destroy();
	}
	
	public GraphicsPipeline createPipeline(VkBuffer vb, List<BufferAndMemory> uniforms, VulkanTexture texture) throws VulkanCodeException{
		var area = new Rect2D(swapchain.extent);
		var p    = new GraphicsPipeline(device);
		
		p.initDescriptor(swapchain.imageViews.size(), List.of(
			new DescriptorSetLayoutBinding(
				0,
				VkDescriptorType.STORAGE_BUFFER,
				1,
				Flags.of(VkShaderStageFlag.VERTEX)
			),
			new DescriptorSetLayoutBinding(
				1,
				VkDescriptorType.UNIFORM_BUFFER,
				1,
				Flags.of(VkShaderStageFlag.VERTEX)
			),
			new DescriptorSetLayoutBinding(
				2,
				VkDescriptorType.COMBINED_IMAGE_SAMPLER,
				1,
				Flags.of(VkShaderStageFlag.FRAGMENT)
			)
		), vb, uniforms, texture);
		p.initPipeline(renderPass, 0, testShaderModules, area, area, physicalDevice.samples);
		return p;
	}
	
	private List<FrameBuffer> createFrameBuffers() throws VulkanCodeException{
		try(var stack = MemoryStack.stackPush()){
			var viewRef = stack.mallocLong(2);
			var info = VkFramebufferCreateInfo.calloc(stack)
			                                  .sType$Default()
			                                  .renderPass(renderPass.handle)
			                                  .pAttachments(viewRef)
			                                  .width(swapchain.extent.width)
			                                  .height(swapchain.extent.height)
			                                  .layers(1);
			
			var views = swapchain.imageViews;
			var fbs   = new ArrayList<FrameBuffer>(views.size());
			for(int i = 0; i<views.size(); i++){
				var mssaView      = mssaImages.get(i).view;
				var swapchainView = views.get(i);
				viewRef.clear().put(mssaView.handle).put(swapchainView.handle).flip();
				fbs.add(VKCalls.vkCreateFramebuffer(device, info));
			}
			return List.copyOf(fbs);
		}
	}
	
	private RenderPass createRenderPass() throws VulkanCodeException{
		
		var mssaAttachment = new RenderPass.AttachmentInfo(
			swapchain.formatColor.format,
			physicalDevice.samples,
			VkAttachmentLoadOp.CLEAR, VkAttachmentStoreOp.STORE,
			VkImageLayout.UNDEFINED, VkImageLayout.COLOR_ATTACHMENT_OPTIMAL
		);
		var presentAttachment = new RenderPass.AttachmentInfo(
			swapchain.formatColor.format,
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
	
	public Optional<QueueFamilyProps> findQueueFamilyBy(VkQueueFlag capability){
		return Iters.from(physicalDevice.families).firstMatching(e -> e.capabilities.contains(capability));
	}
	
	private static final Set<String> WHITELISTED_ERROR_IDS = Set.of();
	private static final Set<String> IGNORE_IDS            = new HashSet<>(Set.of("Loader Message"));
	
	private synchronized boolean debugLogCallback(DebugLoggerEXT.Severity severity, EnumSet<DebugLoggerEXT.Type> messageTypes, String message, String messageIDName){
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
		destroySwapchainContext(true);
		
		try{
			renderQueue.waitIdle();
		}catch(VulkanCodeException e){ e.printStackTrace(); }
		
		transferBuffers.destroy();
		
		testShaderModules.destroy();
		device.destroy();
		surface.destroy();
		if(debugLog != null) debugLog.destroy();
		VK10.vkDestroyInstance(instance, null);
	}
}
