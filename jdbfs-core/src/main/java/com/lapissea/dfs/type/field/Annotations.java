package com.lapissea.dfs.type.field;

import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.NotNull;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.TextUtil;
import com.lapissea.util.WeakValueHashMap;

import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

public abstract class Annotations{
	
	public static IONullability makeNullability(IONullability.Mode mode){
		return make(IONullability.class, Map.of("value", mode));
	}
	
	private static final Object                    NULL_CACHE           = new Object();
	private static final Map<Method, Object>       DEFAULT_VALUES_CACHE = Collections.synchronizedMap(new WeakHashMap<>());
	private static final Map<Class<?>, Annotation> NO_ARG_CACHE         = Collections.synchronizedMap(new WeakValueHashMap<>());
	
	public static <E extends Annotation> E make(Class<E> annotationType){ return make(annotationType, Map.of()); }
	@SuppressWarnings("unchecked")
	public static <E extends Annotation> E make(Class<E> annotationType, @NotNull Map<String, Object> annValues){
		var        values     = Map.copyOf(annValues);
		Class<?>[] interfaces = annotationType.getInterfaces();
		if(!annotationType.isAnnotation() || interfaces.length != 1 || interfaces[0] != Annotation.class){
			throw new IllegalArgumentException(annotationType.getName() + " not an annotation");
		}
		if(values.isEmpty()){
			var cached = NO_ARG_CACHE.get(annotationType);
			if(cached != null) return (E)cached;
		}
		
		var safeValues = Iters.from(annotationType.getDeclaredMethods()).map(element -> {
			String elementName = element.getName();
			if(values.containsKey(elementName)){
				var returnType = getReturnType(element);
				
				if(returnType.isInstance(values.get(elementName))){
					return Map.entry(elementName, values.get(elementName));
				}else{
					throw new IllegalArgumentException("Incompatible type for " + elementName);
				}
			}else{
				var dv = DEFAULT_VALUES_CACHE.get(element);
				if(dv == null){
					dv = element.getDefaultValue();
					DEFAULT_VALUES_CACHE.put(element, dv == null? NULL_CACHE : dv);
				}else if(dv == NULL_CACHE){
					dv = null;
				}
				
				if(dv != null){
					return Map.entry(elementName, dv);
				}else{
					throw new IllegalArgumentException("Missing value " + elementName);
				}
			}
		}).toMap(Map.Entry::getKey, Map.Entry::getValue);
		
		Iters.keys(values).firstNotMatching(safeValues::containsKey).ifPresent(v -> {
			throw new IllegalArgumentException(annotationType.getTypeName() + " does not have value: \"" + v + '"');
		});
		
		int hash = Iters.entries(values).mapToInt(element -> {
			int    res;
			Object val = element.getValue();
			if(val.getClass().isArray()){
				res = 1;
				for(int i = 0; i<Array.getLength(val); i++){
					var el = Array.get(val, i);
					res = 31*res + Objects.hashCode(el);
				}
			}else res = Objects.hashCode(val);
			return (127*element.getKey().hashCode())^res;
		}).sum();
		
		class FakeAnnotation implements Annotation, InvocationHandler{
			
			
			@Override
			public Object invoke(Object proxy, Method method, Object[] args) throws Throwable{
				if(safeValues.containsKey(method.getName())){
					return safeValues.get(method.getName());
				}
				return method.invoke(this, args);
			}
			
			@Override
			public Class<? extends Annotation> annotationType(){
				return annotationType;
			}
			
			@Override
			public boolean equals(Object other){
				if(this == other) return true;
				if(!annotationType.isInstance(other)) return false;
				
				var that    = annotationType.cast(other);
				var thatAnn = that.annotationType();
				
				return Iters.entries(safeValues).allMatch(element -> {
					try{
						var thatVal = thatAnn.getMethod(element.getKey()).invoke(that);
						return Objects.deepEquals(element.getValue(), thatVal);
					}catch(ReflectiveOperationException e){
						throw new RuntimeException(e);
					}
				});
			}
			
			@Override
			public int hashCode(){
				return hash;
			}
			
			@Override
			public String toString(){
				return '@' + annotationType.getName() + TextUtil.toString(safeValues);
			}
		}
		
		
		var proxy = (E)Proxy.newProxyInstance(annotationType.getClassLoader(),
		                                      new Class[]{annotationType},
		                                      new FakeAnnotation());
		
		if(values.isEmpty()){
			NO_ARG_CACHE.put(annotationType, proxy);
		}
		
		return proxy;
	}
	
	private static Class<?> getReturnType(Method element){
		Class<?> returnType = element.getReturnType();
		
		if(returnType.isPrimitive()){
			if(returnType == boolean.class) returnType = Boolean.class;
			else if(returnType == char.class) returnType = Character.class;
			else if(returnType == float.class) returnType = Float.class;
			else if(returnType == double.class) returnType = Double.class;
			else if(returnType == byte.class) returnType = Byte.class;
			else if(returnType == short.class) returnType = Short.class;
			else if(returnType == int.class) returnType = Integer.class;
			else if(returnType == long.class) returnType = Long.class;
			else throw new ShouldNeverHappenError(returnType.toString());
		}
		return returnType;
	}
}
