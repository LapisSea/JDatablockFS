package com.lapissea.dfs.tools.newlogger.display;

import com.lapissea.dfs.utils.iterableplus.Iters;
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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
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
}
