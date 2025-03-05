package com.lapissea.dfs.tools.newlogger.display.vk;

import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.tools.newlogger.display.VUtils;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkQueueCapability;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.DebugLoggerEXT;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Device;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Surface;
import com.lapissea.dfs.tools.newlogger.display.vk.wrap.Swapchain;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.glfw.GlfwWindow;
import com.lapissea.util.ConsoleColors;
import com.lapissea.util.TextUtil;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Pointer;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkExtent2D;
import org.lwjgl.vulkan.VkExtent3D;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkLayerProperties;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.lapissea.dfs.tools.newlogger.display.VUtils.check;
import static org.lwjgl.glfw.GLFWVulkan.glfwGetRequiredInstanceExtensions;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.VK_MAKE_API_VERSION;
import static org.lwjgl.vulkan.VK10.vkCreateInstance;

public class VulkanCore implements AutoCloseable{
	
	static{
		TextUtil.CUSTOM_TO_STRINGS.register(VkExtent2D.class, e -> e.width() + "x" + e.height());
		TextUtil.CUSTOM_TO_STRINGS.register(VkExtent3D.class, e -> e.width() + "x" + e.height() + "x" + e.depth());
		TextUtil.CUSTOM_TO_STRINGS.register(Pointer.class, VUtils::vkObjToString);
	}
	
	public static final int API_VERSION_MAJOR = 1;
	public static final int API_VERSION_MINOR = 1;
	
	
	private final String     name;
	private final VkInstance instance;
	private final Surface    surface;
	private final Device     device;
	private final Swapchain  swapchain;
	
	private final DebugLoggerEXT debugLog;
	
	public VulkanCore(String name, GlfwWindow window){
		this.name = name;
		instance = createInstance();
		debugLog = new DebugLoggerEXT(instance, this::debugLogCallback);
		
		surface = Surface.create(instance, window.getHandle());
		
		var physicalDevices = new PhysicalDevices(instance, surface);
		var physicalDevice  = physicalDevices.selectDevice(VkQueueCapability.GRAPHICS, true);
		
		var queueGraphicsInfo = Iters.from(physicalDevice.families).firstMatching(e -> e.capabilities.contains(VkQueueCapability.GRAPHICS)).orElseThrow();
		Log.info("Using physical device: {}#green", physicalDevice);
		
		device = physicalDevice.createDevice(queueGraphicsInfo);
		swapchain = device.createSwapchain(surface);
		
	}
	
	private synchronized boolean debugLogCallback(DebugLoggerEXT.Severity severity, EnumSet<DebugLoggerEXT.Type> messageTypes, String message, String messageIDName){
		var severityS = Optional.ofNullable(severity).map(e -> e.color + e.name()).orElse(ConsoleColors.RED_BRIGHT + "UNKNOWN") + ConsoleColors.RESET;
		var type      = Iters.from(messageTypes).joinAsOptionalStr(", ").orElse("UNKNOWN");
		
		var log = Iters.from(message.split("\n"))
		               .joinAsStr("\n", line -> Log.fmt("[{}#cyan, {}] [{}#blue]: {}", type, severityS, messageIDName, line));
		
		if(severity == DebugLoggerEXT.Severity.ERROR){
			new RuntimeException("\n" + log).printStackTrace();
			System.exit(1);
			return true;
		}
		Log.log(log);
		return false;
	}
	
	private VkInstance createInstance(){
		try(var stack = MemoryStack.stackPush()){
			
			var availableExtensions = getAvailableExtensionNames(null);
			var availableLayers     = getAvailableLayerNames();

//			LogUtil.println(Iters.from(availableLayers).joinAsStr("\n  ", "LAYERS\n  ", ""));
//			LogUtil.println(Iters.from(availableExtensions).joinAsStr("\n  ", "EXTENSIONS\n  ", ""));
			
			List<String> layerNames = new ArrayList<>();
			if(availableLayers.contains("VK_LAYER_KHRONOS_validation")){
				layerNames.add("VK_LAYER_KHRONOS_validation");
			}else{
				Log.warn("Could not find VK_LAYER_KHRONOS_validation layer! Make sure SDK is installed");
			}
			
			List<String> extraExtension = List.of(
				VK_EXT_DEBUG_UTILS_EXTENSION_NAME
			);
			
			var requiredExtensions = glfwGetRequiredInstanceExtensions();
			if(requiredExtensions == null){
				throw new IllegalStateException("glfwGetRequiredInstanceExtensions failed to find the platform surface extensions.");
			}
			
			
			var extensions = stack.mallocPointer(requiredExtensions.capacity() + extraExtension.size());
			extensions.put(requiredExtensions);
			for(var ext : extraExtension){
				extensions.put(stack.ASCII(ext));
			}
			extensions.flip();
			
			Iters.range(0, extensions.capacity()).mapToLong(extensions::get).mapToObj(MemoryUtil::memUTF8)
			     .filterNot(availableExtensions::contains).joinAsOptionalStr("\n")
			     .ifPresent(missing -> {
				     throw new IllegalStateException("Missing required extension: " + missing);
			     });
			
			Iters.from(layerNames).filterNot(availableLayers::contains).joinAsOptionalStr("\n")
			     .ifPresent(missing -> {
				     throw new IllegalStateException("Missing required layers: " + missing);
			     });
			
			var appInfo = VkApplicationInfo.calloc(stack).sType$Default()
			                               .apiVersion(VK_MAKE_API_VERSION(0, API_VERSION_MAJOR, API_VERSION_MINOR, 0))
			                               .pApplicationName(stack.UTF8(name));
			
			var info = VkInstanceCreateInfo.calloc(stack).sType$Default()
			                               .pApplicationInfo(appInfo)
			                               .ppEnabledLayerNames(VUtils.UTF8ArrayOnStack(stack, layerNames))
			                               .ppEnabledExtensionNames(extensions);
			
			var pp = stack.mallocPointer(1);
			check(vkCreateInstance(info, null, pp), "createInstance");
			return new VkInstance(pp.get(0), info);
		}
	}
	
	private static Set<String> getAvailableExtensionNames(String layerName){
		try(var stack = MemoryStack.stackPush()){
			var instance_extensions = getAvailableExtensions(stack, layerName);
			return Iters.from(instance_extensions).map(VkExtensionProperties::extensionNameString).toSet();
		}
	}
	private static VkExtensionProperties.Buffer getAvailableExtensions(MemoryStack stack, String layerName){
		var countB = stack.mallocInt(1);
		check(VK10.vkEnumerateInstanceExtensionProperties(layerName, countB, null), "enumerateInstanceExtensionProperties");
		
		var instance_extensions = VkExtensionProperties.malloc(countB.get(0), stack);
		check(VK10.vkEnumerateInstanceExtensionProperties(layerName, countB, instance_extensions), "enumerateInstanceExtensionProperties");
		return instance_extensions;
	}
	
	private static Set<String> getAvailableLayerNames(){
		try(var stack = MemoryStack.stackPush()){
			var res = getAvailableLayers(stack);
			return Iters.from(res).map(VkLayerProperties::layerNameString).toSet();
		}
	}
	private static VkLayerProperties.Buffer getAvailableLayers(MemoryStack stack){
		var countB = stack.mallocInt(1);
		check(VK10.vkEnumerateInstanceLayerProperties(countB, null), "enumerateInstanceLayerProperties");
		
		var res = VkLayerProperties.malloc(countB.get(0), stack);
		check(VK10.vkEnumerateInstanceLayerProperties(countB, res), "enumerateInstanceLayerProperties");
		return res;
	}
	
	@Override
	public void close(){
		swapchain.destroy();
		device.destroy();
		surface.destroy();
		debugLog.destroy();
		VK10.vkDestroyInstance(instance, null);
	}
}
