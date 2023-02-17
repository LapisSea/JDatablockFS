package com.lapissea.cfs.type.compilation;

import com.lapissea.cfs.type.SupportedPrimitive;
import com.lapissea.jorth.CodeStream;
import com.lapissea.jorth.MalformedJorth;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.function.UnsafeBiConsumer;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static java.lang.reflect.Modifier.isStatic;

public class JorthUtils{
	
	
	private static void scanAnnotation(Annotation ann, UnsafeBiConsumer<String, Object, MalformedJorth> entry) throws MalformedJorth{
		
		var c = ann.getClass();
		for(Method m : ann.annotationType().getMethods()){
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
		}
	}
	
	static void writeAnnotations(CodeStream writer, List<Annotation> annotations) throws MalformedJorth{
		Set<Class<?>> annTypes = new HashSet<>();
		for(var ann : annotations){
			if(!annTypes.add(ann.annotationType())) continue;
			
			var part = writer.codePart();
			
			boolean[] any = {false};
			
			scanAnnotation(ann, (name, value) -> {
				if(!any[0]){
					any[0] = true;
					writer.write("@ {!} start", ann.annotationType().getName());
				}
				
				writer.write("{!} {}", name, switch(value){
					case null -> "null";
					case String s -> "'" + s.replace("'", "\\'") + "'";
					case Enum<?> e -> e.name();
					case Boolean v -> v.toString();
					case Class<?> c -> c.getName();
					case Number n -> {
						if(SupportedPrimitive.isAny(n.getClass())) yield n + "";
						throw new UnsupportedOperationException();
					}
					case String[] strs -> Arrays.stream(strs).map(s -> "'" + s.replace("'", "\\'") + "'").collect(Collectors.joining(" ", "[", "]"));
					default -> throw new NotImplementedException(value.getClass() + "");
				});
			});
			if(any[0]) writer.write("end");
			else writer.write("@ {!}", ann.annotationType().getName());
			part.close();
		}
	}
	
	static void nullCheck(CodeStream writer) throws MalformedJorth{
		writer.write(
			"""
				static call #Objects requireNonNull start
					dup
				end
				pop
				""");
	}
	
}
