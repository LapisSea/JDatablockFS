package com.lapissea.cfs.type.field.annotations;

import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.type.field.IOField;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * This annotation can specify that another field or fields are dependent on it. This is needed when
 * a custom getter is used and is accessing another IO field.
 */
@AnnotationUsage("Defines field(s) as a dependency. To be used if custom getters/setters are used and another field is referenced")
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface IODependency{
	String[] value();
	
	/**
	 * This annotation enables a number to have a dynamic size with a specified name.
	 * <p>
	 * Multiple fields can have a size of the same name. This will make them share the dynamic size and take the form of the
	 * largest needed size. This can improve performance and efficiency when there are multiple fields of similar values
	 * </p>
	 */
	@Retention(RetentionPolicy.RUNTIME)
	@Target({ElementType.FIELD, ElementType.METHOD})
	@interface NumSize{
		String value();
	}
	
	/**
	 * This annotation enables an array length value to have a dynamic size with a specified name
	 * <p>
	 * Multiple fields can have a size of the same name. This will make them share the dynamic size and take the form of the
	 * largest needed size. This can improve performance and efficiency when there are multiple fields of similar values
	 * </p>
	 */
	@Retention(RetentionPolicy.RUNTIME)
	@Target({ElementType.FIELD, ElementType.METHOD})
	@interface ArrayLenSize{
		String name();
	}
	
	/**
	 * This annotation adds a virtual size field of {@link NumberSize} type. The value if this field is automatically
	 * managed. It can be modified manually through the {@link IOField} api, but it is preferred to simply specify its
	 * behaviour through the values of this annotation.
	 */
	@Retention(RetentionPolicy.RUNTIME)
	@Target({ElementType.FIELD, ElementType.METHOD})
	@interface VirtualNumSize{
		String name() default "";
		
		NumberSize min() default NumberSize.VOID;
		NumberSize max() default NumberSize.LONG;
	}
	
}
