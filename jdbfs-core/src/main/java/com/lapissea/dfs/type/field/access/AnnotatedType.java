package com.lapissea.dfs.type.field.access;

import com.lapissea.dfs.Utils;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.NotNull;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

import static java.util.function.Function.identity;

public interface AnnotatedType{
	
	class Simple implements AnnotatedType{
		
		private final Map<Class<?>, ? extends Annotation> annotations;
		private final Class<?>                            type;
		
		public Simple(Collection<? extends Annotation> annotations, Class<?> type){
			this.annotations = Iters.from(annotations).toMap(Annotation::annotationType, identity());
			this.type = type;
		}
		
		@SuppressWarnings("unchecked")
		@NotNull
		@Override
		public <T extends Annotation> Optional<T> getAnnotation(Class<T> annotationClass){
			return Optional.ofNullable((T)annotations.get(annotationClass));
		}
		
		@Override
		public Type getGenericType(GenericContext genericContext){
			return type;
		}
		@Override
		public Class<?> getType(){
			return type;
		}
	}
	
	@NotNull
	<T extends Annotation> Optional<T> getAnnotation(Class<T> annotationClass);
	default boolean hasAnnotation(Class<? extends Annotation> annotationClass){
		return getAnnotation(annotationClass).isPresent();
	}
	
	
	Type getGenericType(GenericContext genericContext);
	
	default Class<?> getType(){
		var generic = getGenericType(null);
		return Utils.typeToRaw(generic);
	}
}
