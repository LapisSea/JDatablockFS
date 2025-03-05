package com.lapissea.dfs.tools.newlogger.display.vk.wrap;

import com.lapissea.dfs.tools.newlogger.display.vk.VulkanResource;
import com.lapissea.util.ConsoleColors;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTDebugUtils;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackDataEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCreateInfoEXT;
import org.lwjgl.vulkan.VkInstance;

import java.util.EnumSet;
import java.util.Set;

import static com.lapissea.dfs.tools.newlogger.display.VUtils.check;
import static org.lwjgl.vulkan.EXTDebugUtils.*;

public class DebugLoggerEXT implements VulkanResource{
	
	public enum Severity{
		VERBOSE(VK_DEBUG_UTILS_MESSAGE_SEVERITY_VERBOSE_BIT_EXT, ConsoleColors.WHITE),
		INFO(VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT, ConsoleColors.YELLOW),
		WARNING(VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT, ConsoleColors.RED),
		ERROR(VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT, ConsoleColors.RED_BRIGHT);
		
		public final int    bit;
		public final String color;
		
		Severity(int bit, String color){
			this.bit = bit;
			this.color = color;
		}
		public static EnumSet<Severity> from(int props){
			var flags = EnumSet.allOf(Severity.class);
			flags.removeIf(flag -> (flag.bit&props) == 0);
			return flags;
		}
	}
	
	public enum Type{
		GENERAL(VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT),
		VALIDATION(VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT),
		PERFORMANCE(VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT);
		
		public final int bit;
		Type(int bit){ this.bit = bit; }
		
		public static EnumSet<Type> from(int props){
			var flags = EnumSet.allOf(Type.class);
			flags.removeIf(flag -> (flag.bit&props) == 0);
			return flags;
		}
	}
	
	public interface Callback{
		boolean callback(Severity severity, EnumSet<Type> types, String message, String messageIDName);
	}
	
	private final VkInstance instance;
	
	private final VkDebugUtilsMessengerCallbackEXT dbgFunc;
	
	private final long msgCallbackHandle;
	
	private static <T> T pick(Set<T> set){
		return switch(set.size()){
			case 1 -> set.iterator().next();
			default -> null;
		};
	}
	
	public DebugLoggerEXT(VkInstance instance, Callback callback){
		this.instance = instance;
		
		dbgFunc = VkDebugUtilsMessengerCallbackEXT.create((messageSeverity, messageTypes, pCallbackData, pUserData) -> {
			var severity = pick(Severity.from(messageSeverity));
			var type     = Type.from(messageTypes);
			
			//noinspection resource
			var data = VkDebugUtilsMessengerCallbackDataEXT.create(pCallbackData);
			var msg  = data.pMessageString();
			if(msg == null) msg = "";
			
			var idStr = data.pMessageIdNameString();
			
			var res = callback.callback(severity, type, msg, idStr);
			
			return res? VK10.VK_TRUE : VK10.VK_FALSE;
		});
		try{
			msgCallbackHandle = initDebugCallback();
		}catch(Throwable e){
			dbgFunc.close();
			throw e;
		}
	}
	private long initDebugCallback(){
		try(var stack = MemoryStack.stackPush()){
			var cInfo = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack).sType$Default()
			                                              .messageSeverity(VK_DEBUG_UTILS_MESSAGE_SEVERITY_VERBOSE_BIT_EXT|
			                                                               VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT|
			                                                               VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT|
			                                                               VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
			                                              .messageType(VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT|
			                                                           VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT|
			                                                           VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT)
			                                              .pfnUserCallback(dbgFunc);
			
			var callbackB = new long[1];
			check(EXTDebugUtils.vkCreateDebugUtilsMessengerEXT(instance, cInfo, null, callbackB), "createDebugUtilsMessenger");
			return callbackB[0];
		}
	}
	
	
	@Override
	public void destroy(){
		dbgFunc.close();
		EXTDebugUtils.vkDestroyDebugUtilsMessengerEXT(instance, msgCallbackHandle, null);
	}
}
