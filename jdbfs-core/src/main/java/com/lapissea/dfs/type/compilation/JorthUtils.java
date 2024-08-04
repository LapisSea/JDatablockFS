package com.lapissea.dfs.type.compilation;

import com.lapissea.dfs.type.SupportedPrimitive;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.jorth.CodeStream;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.function.UnsafeBiConsumer;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import static java.lang.reflect.Modifier.isStatic;

public final class JorthUtils{
	
	
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
	
	public static void writeAnnotations(CodeStream writer, Iterable<? extends Annotation> annotations) throws MalformedJorth{
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
					case String[] strs -> Iters.from(strs).joinAsStr(" ", "[", "]", s -> "'" + s.replace("'", "\\'") + "'");
					default -> throw new NotImplementedException(value.getClass() + "");
				});
			});
			if(any[0]) writer.write("end");
			else writer.write("@ {!}", ann.annotationType().getName());
			part.close();
		}
	}
	
	static void nullCheckDup(CodeStream writer) throws MalformedJorth{
		nullCheck(writer, "dup");
	}
	static void nullCheckDup(CodeStream writer, String message) throws MalformedJorth{
		nullCheck(writer, "dup", message);
	}
	static void nullCheck(CodeStream writer, CharSequence getFragment) throws MalformedJorth{
		writer.write(
			"""
				static call #Objects requireNonNull start
					{}
				end
				pop
				""",
			getFragment
		);
	}
	static void nullCheck(CodeStream writer, CharSequence getFragment, String message) throws MalformedJorth{
		writer.write(
			"""
				static call #Objects requireNonNull start
					{} '{}'
				end
				pop
				""",
			getFragment, message
		);
	}
	
}
