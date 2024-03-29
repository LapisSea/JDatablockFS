package com.lapissea.dfs.type.field.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@AnnotationUsage("Sets the nullability of a value. Can not be used on non nullable values such as a primitive")
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.TYPE})
public @interface IONullability{
	
	@Target({ElementType.FIELD, ElementType.METHOD})
	@interface Elements{
		Mode value() default Mode.NOT_NULL;
	}
	
	enum Mode{
		NOT_NULL("NN"),
		NULLABLE("n"),
		DEFAULT_IF_NULL("DN");
		
		public final String shortName;
		Mode(String shortName){ this.shortName = shortName; }
	}
	
	Mode value() default Mode.NOT_NULL;
	
}
