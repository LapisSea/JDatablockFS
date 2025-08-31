package com.lapissea.dfs.tools.newlogger.display;

import com.lapissea.dfs.tools.newlogger.display.vk.BackedVkBuffer;
import com.lapissea.dfs.tools.newlogger.display.vk.Flags;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkFormat;
import com.lapissea.dfs.tools.newlogger.display.vk.enums.VkResult;
import com.lapissea.dfs.utils.WeakKeyValueMap;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.NativeType;
import org.lwjgl.system.Pointer;
import org.lwjgl.system.StructBuffer;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
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
		return vkObjToString(obj, false);
	}
	public static String vkObjToString(Pointer obj, boolean typeName){
		if(obj == null) return "null";
		var cls = obj.getClass();
		if(cls.getPackage().getName().startsWith("com.lapissea.dfs.")){
			try{
				cls.getDeclaredMethod("toString");
				return obj.toString();
			}catch(NoSuchMethodException ignored){ }
		}
		var stack = STR_STACK.get();
		var fieldMethods = Iters.from(cls.getDeclaredMethods()).sortedBy(Method::getName).filter(
			e -> e.getParameterCount() == 0 && !Modifier.isStatic(e.getModifiers()) && Modifier.isPublic(e.getModifiers()) &&
			     !e.getName().equals("sizeof") && !e.getName().startsWith("sType")
		).bake();
		
		var nameType = fieldMethods.toMap(Method::getName, Method::getReturnType);
		
		var bbs              = fieldMethods.filter(fn -> fn.getReturnType() == ByteBuffer.class);
		var ignoreBBVariants = bbs.filter(fn -> nameType.get(fn.getName() + "String") == String.class).toSet();
		
		var structBuffers = fieldMethods.filter(fn -> fn.getName().startsWith("p") && UtilL.instanceOf(fn.getReturnType(), StructBuffer.class))
		                                .map(Method::getName).toSet();
		var bufferLengths = structBuffers.isEmpty()? Set.of() :
		                    fieldMethods.filter(fn -> {
			                    if(fn.getReturnType() != int.class) return false;
			                    var name       = fn.getName();
			                    var noCount    = name.endsWith("Count")? name.substring(0, name.length() - "Count".length()) : name;
			                    var plural     = TextUtil.plural(noCount);
			                    var bufferName = "p" + TextUtil.firstToUpperCase(plural);
			                    return structBuffers.contains(bufferName);
		                    }).toSet();
		
		
		return fieldMethods.filterNot(ignoreBBVariants::contains).filterNot(bufferLengths::contains).map(fn -> {
			try{
				var el = fn.invoke(obj);
				if(el == null) return null;
				if(el instanceof Number n && n.longValue() == 0) return null;
				var loop = el instanceof Pointer && stack.contains(el);
				if(!loop && !(el instanceof Pointer)) stack.add(el);
				try{
					String val;
					if(loop) val = el.toString();
					else{
						String eStr = null;
						if(el instanceof Integer iEl && fn.getAnnotation(NativeType.class) instanceof NativeType t){
							try{
								var className = t.value();
								if(className.endsWith("Bits")) className = className.substring(0, className.length() - 4);
								if(className.endsWith("Flags")) className = className.substring(0, className.length() - 1);
								var ec = Class.forName(VkResult.class.getPackageName() + "." + className);
								if(UtilL.instanceOf(ec, FlagSetValue.class)){
									eStr = new Flags<>((Class)ec, iEl).toString();
								}else if(UtilL.instanceOf(ec, IDValue.class)){
									try{
										eStr = fromID((Class)ec, iEl).toString();
									}catch(IllegalArgumentException e){ }
								}
							}catch(ClassNotFoundException e){ }
						}
						if(eStr != null) val = eStr;
						else if(el instanceof StructBuffer<?, ?> sb){
							val = Iters.from(sb).map(TextUtil::toShortString).joinAsStr(", ", "[", "]");
						}else{
							val = TextUtil.toString(el);
						}
					}
					return fn.getName() + ": " + val;
				}finally{
					if(!loop) stack.remove(el);
				}
			}catch(IllegalAccessException|InvocationTargetException e){
				return fn.getName() + ": <ERROR: " + e + ">";
			}
		}).filter(Objects::nonNull).joinAsStr(", ", typeName? cls.getSimpleName() + "{" : "{", "}");
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
	
	public static StringBuilder readResourceAsStr(String resource) throws IOException{
		URL url = VUtils.class.getResource(resource.startsWith("/")? resource : "/" + resource);
		if(url == null){
			throw new IOException("Resource not found: " + resource);
		}
		var buffer = new StringBuilder();
		try(var source = url.openStream()){
			if(source == null){
				throw new FileNotFoundException(resource);
			}
			var buf = new char[512];
			try(var reader = new InputStreamReader(source)){
				int read;
				while((read = reader.read(buf)) != -1){
					buffer.append(buf, 0, read);
				}
			}
		}
		return buffer;
	}
	public static ByteBuffer readResource(String resource) throws IOException{
		URL url = VUtils.class.getResource(resource.startsWith("/")? resource : "/" + resource);
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
	
	public static short toHalfFloat(final float v){
		if(Float.isNaN(v)) throw new UnsupportedOperationException("NaN to half conversion not supported!");
		if(v == Float.POSITIVE_INFINITY) return (short)0x7c00;
		if(v == Float.NEGATIVE_INFINITY) return (short)0xfc00;
		if(v == 0.0f) return (short)0x0000;
		if(v == -0.0f) return (short)0x8000;
		if(v>65504.0f) return 0x7bff;  // max value supported by half float
		if(v<-65504.0f) return (short)(0x7bff|0x8000);
		if(v>0.0f && v<5.96046E-8f) return 0x0001;
		if(v<0.0f && v>-5.96046E-8f) return (short)0x8001;
		
		final int f = Float.floatToIntBits(v);
		
		return (short)(((f>>16)&0x8000)|((((f&0x7f800000) - 0x38000000)>>13)&0x7c00)|((f>>13)&0x03ff));
	}
	public static float toFloat(final short half){
		return switch((int)half){
			case 0x0000 -> 0.0f;
			case 0x8000 -> -0.0f;
			case 0x7c00 -> Float.POSITIVE_INFINITY;
			case 0xfc00 -> Float.NEGATIVE_INFINITY;
			default -> Float.intBitsToFloat(((half&0x8000)<<16)|(((half&0x7c00) + 0x1C000)<<13)|((half&0x03FF)<<13));
		};
	}
	
	public static int toRGBAi4(Color color){
		return color.getRed()|(color.getGreen()<<8)|(color.getBlue()<<16)|(color.getAlpha()<<24);
	}
	
	public static void copyDestroy(DeviceGC deviceGC, BackedVkBuffer oldBuff, BackedVkBuffer newBuff) throws VulkanCodeException{
		if(oldBuff == null) return;
		oldBuff.transferTo(newBuff);
		deviceGC.destroyLater(oldBuff);
	}
}
