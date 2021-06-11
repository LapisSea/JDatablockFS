package com.lapissea.cfs.type.compilation;

import com.lapissea.cfs.IterablePP;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.access.IFieldAccessor;
import com.lapissea.cfs.type.field.access.ReflectionAccessor;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.util.UtilL;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Stream;

public abstract class FieldCompiler{
	
	protected record LogicalAnnotation<T extends Annotation>(T annotation, AnnotationLogic<T> logic){}
	
	public static FieldCompiler create(){
		return new ReflectionFieldCompiler();
	}
	
	public abstract <T extends IOInstance<T>> List<IOField<T, ?>> compile(Struct<T> struct);
	
	protected abstract RegistryNode.Registry registry();
	
	protected IterablePP<Field> deepFieldsByAnnotation(Class<?> clazz, Class<? extends Annotation> type){
		return IterablePP
			       .nullTerminated(()->new Supplier<Class<?>>(){
				       Class<?> c=clazz;
				       @Override
				       public Class<?> get(){
					       if(c==null) return null;
					       var tmp=c;
					       var cp =c.getSuperclass();
					       c=cp==c?null:cp;
					
					       return tmp;
				       }
			       })
			       .flatMap(c->Arrays.asList(c.getDeclaredFields()).iterator())
			       .filtered(f->f.isAnnotationPresent(type));
	}
	
	protected <T extends IOInstance<T>> List<IOField<T, ?>> scanFields(Struct<T> struct){
		var cl=struct.getType();
		
		List<IFieldAccessor<T>> fields=new ArrayList<>();
		
		var registry=registry();
		
		for(Field field : deepFieldsByAnnotation(cl, IOValue.class)){
			registry.requireCanCreate(field.getGenericType());
			field.setAccessible(true);
			
			var name=field.getAnnotation(IOValue.class).name();
			if(name.isEmpty()) name=field.getName();
			
			UtilL.addRemainSorted(fields, new ReflectionAccessor<>(struct, field, name));
		}
		
		List<IOField<T, ?>> parsed=new ArrayList<>(fields.size());
		for(var f : fields){
			parsed.add(registry.create(f));
		}
		
		return parsed;
	}
	
	@SuppressWarnings("unchecked")
	protected <T extends IOInstance<T>> List<LogicalAnnotation<Annotation>> scanAnnotations(IOField<T, ?> field){
		return activeAnnotations().stream().flatMap(ann->Stream.concat(Stream.of(ann), Arrays.stream(ann.getClasses()))).map(t->{
			var ann=field.getAccessor().getAnnotation((Class<? extends Annotation>)t);
			if(ann==null) return null;
			try{
				Field logic=t.getField("LOGIC");
				
				if(!(logic.getGenericType() instanceof ParameterizedType parmType&&
				     AnnotationLogic.class.equals(parmType.getRawType())&&
				     Arrays.equals(parmType.getActualTypeArguments(), new Type[]{t}))){
					
					throw new ClassCastException(logic+" is not a type of "+AnnotationLogic.class.getName()+"<"+t.getName()+">");
				}
				
				return new LogicalAnnotation<>(ann, (AnnotationLogic<Annotation>)logic.get(null));
			}catch(NoSuchFieldException|IllegalAccessException e){
				throw new RuntimeException("Class "+t.getName()+" does not contain an AnnotationLogic LOGIC field", e);
			}
		}).filter(Objects::nonNull).toList();
	}
	
	protected abstract Set<Class<? extends Annotation>> activeAnnotations();
	
}
