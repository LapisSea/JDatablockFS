package com.lapissea.cfs.io.struct;

import com.lapissea.cfs.io.struct.engine.StructReflectionImpl;
import com.lapissea.util.ObjectHolder;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.lapissea.util.UtilL.*;

public interface StructImpl{
	
	record Annotated<L, A extends Annotation>(L val, A annotation){
		
		private record ElementDef<K>(
			Function<Class<?>, Stream<K>> getter,
			BiFunction<K, Class<Annotation>, Annotation> getAnnotation
		){}
		
		private static <L, K> void collect(Class<L> clazz,
		                                   Map<Class<Annotation>, AnnotationRelations<Annotation>> destination,
		                                   ElementDef<K> eDef,
		                                   Function<AnnotationRelations<Annotation>, List<Annotated<K, Annotation>>> selector){
			ObjectHolder<Class<?>> c=new ObjectHolder<>(clazz);
			Stream
				.generate(()->{
					if(c.obj==null) return null;
					try{
						return eDef.getter.apply(c.obj);
					}finally{
						c.obj=c.obj.getSuperclass();
					}
				})
				.takeWhile(Objects::nonNull)
				.flatMap(s->s)
				.distinct()
				.map(obj->IOStruct.ANNOTATIONS.stream()
				                              .map(annType->eDef.getAnnotation.apply(obj, annType))
				                              .filter(Objects::nonNull)
				                              .findAny()
				                              .map(a->new Annotated<>(obj, a)).orElse(null))
				.filter(Objects::nonNull)
				.forEach(annotated->{
					Class<Annotation> annType     =getAnnotationInterface(annotated.annotation());
					var               relation    =destination.computeIfAbsent(annType, cl->new AnnotationRelations<>(new ArrayList<>(), new ArrayList<>()));
					var               relationPart=selector.apply(relation);
					relationPart.add(annotated);
				});
		}
	}
	
	record AnnotationRelations<A extends Annotation>(List<Annotated<Method, A>> methods, List<Annotated<Field, A>> fields){}
	
	@SuppressWarnings("unchecked")
	class RelationCollection{
		@SuppressWarnings("rawtypes")
		private static final AnnotationRelations EMPTY=new AnnotationRelations(List.of(), List.of());
		
		private final Map<Class<Annotation>, AnnotationRelations<Annotation>> map;
		
		public RelationCollection(){
			this.map=new HashMap<>();
		}
		
		public <L extends Annotation> AnnotationRelations<L> get(Class<L> type){
			return (AnnotationRelations<L>)map.getOrDefault(type, EMPTY);
		}
	}
	
	
	StructImpl DEFAULT_IMPL=new StructReflectionImpl();
	
	static <T extends IOInstance> List<VariableNode<Object>> generateVariablesDefault(Class<T> clazz){
		return DEFAULT_IMPL.generateVariables(clazz);
	}
	
	default <T extends IOInstance> List<VariableNode<Object>> generateVariables(Class<T> clazz){
		RelationCollection data=new RelationCollection();
		
		Annotated.collect(clazz, data.map, new Annotated.ElementDef<>(c->Arrays.stream(c.getDeclaredMethods()).filter(e->!Modifier.isStatic(e.getModifiers())), Method::getAnnotation), AnnotationRelations::methods);
		Annotated.collect(clazz, data.map, new Annotated.ElementDef<>(c->Arrays.stream(c.getDeclaredFields()).filter(e->!Modifier.isStatic(e.getModifiers())), Field::getAnnotation), AnnotationRelations::fields);
		
		return generateVariables(clazz, data);
	}
	
	<T extends IOInstance> List<VariableNode<Object>> generateVariables(Class<T> clazz, RelationCollection data);
	
}
