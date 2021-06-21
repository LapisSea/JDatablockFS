package com.lapissea.cfs.type;

import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

public interface GetAnnotation{
	
	static GetAnnotation from(Collection<? extends Annotation> data){
		return from(data.stream().collect(Collectors.toMap(Annotation::annotationType, an->an)));
	}
	static GetAnnotation from(Map<Class<? extends Annotation>, Annotation> data){
		if(data.isEmpty()) return new GetAnnotation(){
			@Override
			public <T extends Annotation> T get(Class<T> annotationClass){
				return null;
			}
		};
		return new GetAnnotation(){
			@Override
			public <T extends Annotation> T get(Class<T> annotationClass){
				return annotationClass.cast(data.get(annotationClass));
			}
		};
	}
	
	<T extends Annotation> T get(Class<T> annotationClass);
	default boolean isPresent(Class<? extends Annotation> annotationClass){
		return get(annotationClass)!=null;
	}
}
