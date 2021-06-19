package com.lapissea.cfs.type;

import java.lang.annotation.Annotation;
import java.util.Map;

public interface GetAnnotation{
	
	static GetAnnotation from(Map<Class<? extends Annotation>, Annotation> data){
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
