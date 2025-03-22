package com.lapissea.dfs.tools.newlogger.display;

import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkFormat;
import com.lapissea.dfs.utils.WeakKeyValueMap;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.TextUtil;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Pointer;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
	
	public static List<String> UTF8ArrayToJava(PointerBuffer strings){
		if(strings == null) return null;
		return Iters.rangeMap(0, strings.capacity(), i -> MemoryUtil.memUTF8(strings.get(i))).toList();
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
	
	public interface IDValue{
		int id();
	}
	
	public interface FlagSetValue{
		int bit();
	}
	
	private static final WeakKeyValueMap<Class<?>, Map<Integer, ?>> ID_CACHE = new WeakKeyValueMap.Sync<>();
	
	public static <E extends Enum<E> & IDValue> E fromID(Class<E> type, int id){
		//noinspection unchecked
		Map<Integer, E> idCache = (Map<Integer, E>)ID_CACHE.get(type);
		if(idCache == null){
			ID_CACHE.put(type, idCache = enumToIDs(type));
		}
		var res = idCache.get(id);
		if(res == null){
			throw new IllegalArgumentException("Unknown ID " + id + " for type " + type);
		}
		return res;
	}
	public static <E extends Enum<E> & IDValue> Map<Integer, E> enumToIDs(Class<E> type){
		var uni = Iters.from(type);
		return uni.mapToInt(IDValue::id).distinct()
		          .toMap(id -> id, id -> uni.firstMatching(e -> e.id() == id).orElseThrow());
	}
	public static ByteBuffer nativeMemCopy(ByteBuffer heap) throws IOException{
		return MemoryUtil.memAlloc(heap.remaining()).put(heap).flip();
	}
	
	public static ByteBuffer readResource(String resource) throws IOException{
		URL url = VUtils.class.getResource("/" + resource);
		if(url == null){
			throw new IOException("Resource not found: " + resource);
		}
		var buffer = new ByteArrayOutputStream(){
			byte[] buf(){ return this.buf; }
		};
		try(var source = url.openStream()){
			if(source == null){
				throw new FileNotFoundException(resource);
			}
			source.transferTo(buffer);
		}
		
		return ByteBuffer.wrap(buffer.buf()).order(ByteOrder.nativeOrder()).limit(buffer.size());
	}
	public static int getBytesPerPixel(VkFormat format){
		return switch(format){
			case R8_SINT, R8_UNORM -> 1;
			case R16_SFLOAT -> 2;
			case R16G16_SNORM -> 4;
			case R8G8B8A8_UNORM -> 4;
			case R16G16B16A16_SFLOAT -> 4*2;
			case R32G32B32A32_SFLOAT -> 4*Float.SIZE;
			default -> throw new NotImplementedException("Unexpected format: " + format);
		};
	}
}
