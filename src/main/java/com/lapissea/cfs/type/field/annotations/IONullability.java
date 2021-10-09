package com.lapissea.cfs.type.field.annotations;

import com.lapissea.cfs.exceptions.MalformedStructLayout;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.compilation.AnnotationLogic;
import com.lapissea.cfs.type.field.access.IFieldAccessor;
import com.lapissea.util.UtilL;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.stream.Stream;

@Retention(RetentionPolicy.RUNTIME)
public @interface IONullability{
	
	AnnotationLogic<IONullability> LOGIC=new AnnotationLogic<>(){
		@Override
		public void validate(IFieldAccessor<?> field, IONullability annotation){
			var typ=field.getType();
			if(Stream.of(IOInstance.class, Enum.class).noneMatch(c->UtilL.instanceOf(typ, c))&&!field.hasAnnotation(IOType.Dynamic.class)){
				throw new MalformedStructLayout(field+" is not a supported field");
			}
		}
	};
	
	enum Mode{
		NOT_NULL,
		NULLABLE,
		DEFAULT_IF_NULL
	}
	
	Mode value() default Mode.NOT_NULL;
	
}
