package com.lapissea.dfs.type;

import com.lapissea.dfs.type.field.access.FieldAccessor;

import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

public interface GetAnnotation{
	
	static GetAnnotation from(FieldAccessor<?> accessor){
		return new GetAnnotation(){
			@Override
			public <A extends Annotation> A get(Class<A> annotationClass){
				return accessor.getAnnotation(annotationClass).orElse(null);
			}
			@Override
			public boolean isPresent(Class<? extends Annotation> annotationClass){
				return accessor.hasAnnotation(annotationClass);
			}
		};
	}
	
	static GetAnnotation from(Collection<? extends Annotation> data){
		return from(data.stream().collect(Collectors.toUnmodifiableMap(Annotation::annotationType, an -> an)));
	}
	static GetAnnotation from(Map<Class<? extends Annotation>, ? extends Annotation> data){
		if(data.isEmpty()) return new GetAnnotation(){
			@Override
			public <T extends Annotation> T get(Class<T> annotationClass){
				return null;
			}
			@Override
			public boolean isPresent(Class<? extends Annotation> annotationClass){
				return false;
			}
			@Override
			public String toString(){
				return "{}";
			}
		};
		var dataFinal = Map.copyOf(data);
		return new GetAnnotation(){
			@Override
			public <T extends Annotation> T get(Class<T> annotationClass){
				return annotationClass.cast(dataFinal.get(annotationClass));
			}
			@Override
			public boolean isPresent(Class<? extends Annotation> annotationClass){
				return dataFinal.containsKey(annotationClass);
			}
			@Override
			public String toString(){
				return dataFinal.keySet().stream().map(Class::getSimpleName).collect(Collectors.joining(", ", "{", "}"));
			}
		};
	}
	
	<T extends Annotation> T get(Class<T> annotationClass);
	default boolean isPresent(Class<? extends Annotation> annotationClass){
		return get(annotationClass) != null;
	}
}
