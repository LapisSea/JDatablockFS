package com.lapissea.dfs.tools.newlogger.display;

import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.TextUtil;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.Pointer;
import org.lwjgl.vulkan.EXTDebugReport;
import org.lwjgl.vulkan.EXTFullScreenExclusive;
import org.lwjgl.vulkan.EXTImageCompressionControl;
import org.lwjgl.vulkan.EXTImageDrmFormatModifier;
import org.lwjgl.vulkan.EXTShaderObject;
import org.lwjgl.vulkan.KHRDeferredHostOperations;
import org.lwjgl.vulkan.KHRDisplaySwapchain;
import org.lwjgl.vulkan.KHRPipelineBinary;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.KHRVideoEncodeQueue;
import org.lwjgl.vulkan.KHRVideoQueue;
import org.lwjgl.vulkan.NVGLSLShader;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VK14;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public final class VUtils{
	
	static BufferedImage createVulkanIcon(int width, int height){
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D    g2d   = image.createGraphics();
		
		g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g2d.setComposite(AlphaComposite.Clear);
		g2d.fillRect(0, 0, width, height);
		
		g2d.setComposite(AlphaComposite.SrcOver);
		
		int[] xPoints = {width/2, width - 10, 10};
		int[] yPoints = {10, height - 10, height - 10};
		
		GradientPaint gradient = new GradientPaint(
			(float)width/2, 10, new Color(80, 150, 255, 200), // Lighter red at top
			(float)width/2, height - 10, new Color(150, 0, 0, 200) // Darker red at bottom
		);
		
		g2d.setPaint(gradient);
		g2d.fillPolygon(xPoints, yPoints, 3);
		
		g2d.dispose();
		return image;
	}
	
	public static void check(int errorCode, String action){
		if(errorCode == VK10.VK_SUCCESS) return;
		fail(errorCode, action);
	}
	private static void fail(int errorCode, String action){
		var name = switch(errorCode){
			case VK10.VK_SUCCESS -> throw new ShouldNeverHappenError();
			case VK10.VK_NOT_READY -> "VK_NOT_READY";
			case VK10.VK_TIMEOUT -> "VK_TIMEOUT";
			case VK10.VK_EVENT_SET -> "VK_EVENT_SET";
			case VK10.VK_EVENT_RESET -> "VK_EVENT_RESET";
			case VK10.VK_INCOMPLETE -> "VK_INCOMPLETE";
			case VK10.VK_ERROR_OUT_OF_HOST_MEMORY -> "VK_ERROR_OUT_OF_HOST_MEMORY";
			case VK10.VK_ERROR_OUT_OF_DEVICE_MEMORY -> "VK_ERROR_OUT_OF_DEVICE_MEMORY";
			case VK10.VK_ERROR_INITIALIZATION_FAILED -> "VK_ERROR_INITIALIZATION_FAILED";
			case VK10.VK_ERROR_DEVICE_LOST -> "VK_ERROR_DEVICE_LOST";
			case VK10.VK_ERROR_MEMORY_MAP_FAILED -> "VK_ERROR_MEMORY_MAP_FAILED";
			case VK10.VK_ERROR_LAYER_NOT_PRESENT -> "VK_ERROR_LAYER_NOT_PRESENT";
			case VK10.VK_ERROR_EXTENSION_NOT_PRESENT -> "VK_ERROR_EXTENSION_NOT_PRESENT";
			case VK10.VK_ERROR_FEATURE_NOT_PRESENT -> "VK_ERROR_FEATURE_NOT_PRESENT";
			case VK10.VK_ERROR_INCOMPATIBLE_DRIVER -> "VK_ERROR_INCOMPATIBLE_DRIVER";
			case VK10.VK_ERROR_TOO_MANY_OBJECTS -> "VK_ERROR_TOO_MANY_OBJECTS";
			case VK10.VK_ERROR_FORMAT_NOT_SUPPORTED -> "VK_ERROR_FORMAT_NOT_SUPPORTED";
			case VK10.VK_ERROR_FRAGMENTED_POOL -> "VK_ERROR_FRAGMENTED_POOL";
			case VK10.VK_ERROR_UNKNOWN -> "VK_ERROR_UNKNOWN";
			case VK11.VK_ERROR_OUT_OF_POOL_MEMORY -> "VK_ERROR_OUT_OF_POOL_MEMORY";
			case VK11.VK_ERROR_INVALID_EXTERNAL_HANDLE -> "VK_ERROR_INVALID_EXTERNAL_HANDLE";
			case VK12.VK_ERROR_FRAGMENTATION -> "VK_ERROR_FRAGMENTATION";
			case VK12.VK_ERROR_INVALID_OPAQUE_CAPTURE_ADDRESS -> "VK_ERROR_INVALID_OPAQUE_CAPTURE_ADDRESS";
			case VK13.VK_PIPELINE_COMPILE_REQUIRED -> "VK_PIPELINE_COMPILE_REQUIRED";
			case VK14.VK_ERROR_NOT_PERMITTED -> "VK_ERROR_NOT_PERMITTED";
			case KHRSurface.VK_ERROR_SURFACE_LOST_KHR -> "VK_ERROR_SURFACE_LOST_KHR";
			case KHRSurface.VK_ERROR_NATIVE_WINDOW_IN_USE_KHR -> "VK_ERROR_NATIVE_WINDOW_IN_USE_KHR";
			case KHRSwapchain.VK_SUBOPTIMAL_KHR -> "VK_SUBOPTIMAL_KHR";
			case KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR -> "VK_ERROR_OUT_OF_DATE_KHR";
			case KHRDisplaySwapchain.VK_ERROR_INCOMPATIBLE_DISPLAY_KHR -> "VK_ERROR_INCOMPATIBLE_DISPLAY_KHR";
			case EXTDebugReport.VK_ERROR_VALIDATION_FAILED_EXT -> "VK_ERROR_VALIDATION_FAILED_EXT";
			case NVGLSLShader.VK_ERROR_INVALID_SHADER_NV -> "VK_ERROR_INVALID_SHADER_NV";
			case KHRVideoQueue.VK_ERROR_IMAGE_USAGE_NOT_SUPPORTED_KHR -> "VK_ERROR_IMAGE_USAGE_NOT_SUPPORTED_KHR";
			case KHRVideoQueue.VK_ERROR_VIDEO_PICTURE_LAYOUT_NOT_SUPPORTED_KHR -> "VK_ERROR_VIDEO_PICTURE_LAYOUT_NOT_SUPPORTED_KHR";
			case KHRVideoQueue.VK_ERROR_VIDEO_PROFILE_OPERATION_NOT_SUPPORTED_KHR -> "VK_ERROR_VIDEO_PROFILE_OPERATION_NOT_SUPPORTED_KHR";
			case KHRVideoQueue.VK_ERROR_VIDEO_PROFILE_FORMAT_NOT_SUPPORTED_KHR -> "VK_ERROR_VIDEO_PROFILE_FORMAT_NOT_SUPPORTED_KHR";
			case KHRVideoQueue.VK_ERROR_VIDEO_PROFILE_CODEC_NOT_SUPPORTED_KHR -> "VK_ERROR_VIDEO_PROFILE_CODEC_NOT_SUPPORTED_KHR";
			case KHRVideoQueue.VK_ERROR_VIDEO_STD_VERSION_NOT_SUPPORTED_KHR -> "VK_ERROR_VIDEO_STD_VERSION_NOT_SUPPORTED_KHR";
			case EXTImageDrmFormatModifier.VK_ERROR_INVALID_DRM_FORMAT_MODIFIER_PLANE_LAYOUT_EXT ->
				"VK_ERROR_INVALID_DRM_FORMAT_MODIFIER_PLANE_LAYOUT_EXT";
			case EXTFullScreenExclusive.VK_ERROR_FULL_SCREEN_EXCLUSIVE_MODE_LOST_EXT -> "VK_ERROR_FULL_SCREEN_EXCLUSIVE_MODE_LOST_EXT";
			case KHRDeferredHostOperations.VK_THREAD_IDLE_KHR -> "VK_THREAD_IDLE_KHR";
			case KHRDeferredHostOperations.VK_THREAD_DONE_KHR -> "VK_THREAD_DONE_KHR";
			case KHRDeferredHostOperations.VK_OPERATION_DEFERRED_KHR -> "VK_OPERATION_DEFERRED_KHR";
			case KHRDeferredHostOperations.VK_OPERATION_NOT_DEFERRED_KHR -> "VK_OPERATION_NOT_DEFERRED_KHR";
			case KHRVideoEncodeQueue.VK_ERROR_INVALID_VIDEO_STD_PARAMETERS_KHR -> "VK_ERROR_INVALID_VIDEO_STD_PARAMETERS_KHR";
			case EXTImageCompressionControl.VK_ERROR_COMPRESSION_EXHAUSTED_EXT -> "VK_ERROR_COMPRESSION_EXHAUSTED_EXT";
			case EXTShaderObject.VK_INCOMPATIBLE_SHADER_BINARY_EXT -> "VK_INCOMPATIBLE_SHADER_BINARY_EXT";
			case KHRPipelineBinary.VK_PIPELINE_BINARY_MISSING_KHR -> "VK_PIPELINE_BINARY_MISSING_KHR";
			case KHRPipelineBinary.VK_ERROR_NOT_ENOUGH_SPACE_KHR -> "VK_ERROR_NOT_ENOUGH_SPACE_KHR";
			default -> "<CODE " + errorCode + ">";
		};
		throw new IllegalStateException(Log.fmt("Failed to call {}#red: {}#red", action, name));
	}
	
	public static PointerBuffer UTF8ArrayOnStack(MemoryStack stack, String... strings){
		return UTF8ArrayOnStack(stack, Arrays.asList(strings));
	}
	public static PointerBuffer UTF8ArrayOnStack(MemoryStack stack, Collection<String> strings){
		var ptrs = stack.mallocPointer(strings.size());
		for(var str : strings){
			ptrs.put(stack.UTF8(str));
		}
		ptrs.flip();
		return ptrs;
	}
	
	private static final ThreadLocal<Set<Object>> STR_STACK = ThreadLocal.withInitial(HashSet::new);
	
	public static String vkObjToString(Pointer obj){
		var cls   = obj.getClass();
		var stack = STR_STACK.get();
		var fieldMethods = Iters.from(cls.getDeclaredMethods()).sortedBy(Method::getName).filter(
			e -> e.getParameterCount() == 0 && !Modifier.isStatic(e.getModifiers()) && Modifier.isPublic(e.getModifiers()) &&
			     !e.getName().equals("sizeof") && !e.getName().startsWith("sType")
		).bake();
		
		var nameType = fieldMethods.toMap(Method::getName, Method::getReturnType);
		
		var bbs              = fieldMethods.filter(fn -> fn.getReturnType() == ByteBuffer.class);
		var ignoreBBVariants = bbs.filter(fn -> nameType.get(fn.getName() + "String") == String.class).toSet();
		
		return fieldMethods.filterNot(ignoreBBVariants::contains).map(fn -> {
			try{
				var el = fn.invoke(obj);
				if(el == null) return null;
				if(el instanceof Number n && n.longValue() == 0) return null;
				var loop = stack.contains(el);
				if(!loop) stack.add(el);
				try{
					return fn.getName() + ": " + (loop? el.toString() : TextUtil.toString(el));
				}finally{
					if(!loop) stack.remove(el);
				}
			}catch(IllegalAccessException|InvocationTargetException e){
				return fn.getName() + ": <ERROR: " + e + ">";
			}
		}).filter(Objects::nonNull).joinAsStr(", ", cls.getSimpleName() + "{", "}");
	}
}
