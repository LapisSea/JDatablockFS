package com.lapissea.cfs.type.field;

import com.lapissea.cfs.IterablePP;
import com.lapissea.cfs.type.IOField;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

public abstract class FieldCompiler{
	public static FieldCompiler create(){
		return new ReflectionFieldCompiler();
	}
	
	public abstract <T extends IOInstance<T>> List<IOField<T, ?>> compile(Struct<T> struct);
	
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
	
}
