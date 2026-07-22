package com.lapissea.dfs.type.compilation;

import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.redo.AnnotationContainer;
import com.lapissea.jorth.redo.CodeArg;
import com.lapissea.jorth.redo.CodeBlock;
import com.lapissea.util.function.UnsafeBiConsumer;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

import static java.lang.reflect.Modifier.isStatic;

public final class JorthUtils{
	
	private static final Map<Class<?>, Set<String>> ANNOTATION_NAMES_CACHE = Collections.synchronizedMap(new WeakHashMap<>());
	
	private static void scanAnnotation(Annotation ann, UnsafeBiConsumer<String, Object, MalformedJorth> entry) throws MalformedJorth{
		var type   = ann.annotationType();
		var cached = ANNOTATION_NAMES_CACHE.get(type);
		if(cached != null){
			scanCached(ann, entry, cached);
			return;
		}
		
		var names = scanNonCached(ann, entry);
		ANNOTATION_NAMES_CACHE.put(type, names);
	}
	
	private static Set<String> scanNonCached(Annotation ann, UnsafeBiConsumer<String, Object, MalformedJorth> entry) throws MalformedJorth{
		var type  = ann.annotationType();
		var names = new ArrayList<String>();
		var c     = ann.getClass();
		for(Method m : type.getMethods()){
			if(m.getParameterCount() != 0) continue;
			if(isStatic(m.getModifiers())) continue;
			
			if(m.getName().equals("annotationType")) continue;
			
			try{
				c.getSuperclass().getMethod(m.getName());
				continue;
			}catch(NoSuchMethodException ignored){ }
			Object val;
			try{
				m.setAccessible(true);
				val = m.invoke(ann);
			}catch(Throwable e){
				throw new RuntimeException(e);
			}
			entry.accept(m.getName(), val);
			names.add(m.getName());
		}
		return Set.copyOf(names);
	}
	private static void scanCached(Annotation ann, UnsafeBiConsumer<String, Object, MalformedJorth> entry, Set<String> names) throws MalformedJorth{
		var type = ann.annotationType();
		for(var m : type.getMethods()){
			var name = m.getName();
			if(!names.contains(name)) continue;
			Object val;
			try{
				m.setAccessible(true);
				val = m.invoke(ann);
			}catch(Throwable e){
				throw new RuntimeException(e);
			}
			entry.accept(name, val);
		}
	}
	
	public static void writeAnnotations(AnnotationContainer<?> target, Iterable<? extends Annotation> annotations) throws MalformedJorth{
		Set<Class<?>> annTypes = new HashSet<>();
		for(var ann : annotations){
			if(!annTypes.add(ann.annotationType())) continue;
			
			LinkedHashMap<String, Object> args = new LinkedHashMap<>();
			scanAnnotation(ann, args::put);
			target.annotation(ann.annotationType(), args);
		}
	}
	
	static void nullCheckDup(CodeBlock body) throws MalformedJorth{
		nullCheck(body, CodeBlock::dup);
	}
	static void nullCheckDup(CodeBlock body, String message) throws MalformedJorth{
		nullCheck(body, CodeBlock::dup, message);
	}
	static void nullCheck(CodeBlock body, CodeArg getArg) throws MalformedJorth{
		body.call(Objects.class, "requireNonNull", getArg)
		    .pop();
	}
	static void nullCheck(CodeBlock body, CodeArg getArg, String message) throws MalformedJorth{
		body.call(Objects.class, "requireNonNull", args -> {
			    getArg.accept(args);
			    args.val(message);
		    })
		    .pop();
	}
	
}
