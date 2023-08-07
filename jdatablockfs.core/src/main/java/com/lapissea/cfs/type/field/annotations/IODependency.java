package com.lapissea.cfs.type.field.annotations;

import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.type.field.IOField;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static com.lapissea.cfs.type.field.annotations.IODependency.VirtualNumSize.RetentionPolicy.GHOST;

/**
 * This annotation can specify that another field or fields are dependent on it. This is needed when
 * a custom getter is used and is accessing another IO field.
 */
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
		
		/**
		 * This enum specifies the logic that determines the behaviour of the automatic computation of the virtual field value.
		 */
		enum RetentionPolicy{
			/**
			 * This policy states that a field should simply conform to the minimum valid size of the number. This value may grow
			 * or shrink without any restriction. This is the default and preferred mode.
			 */
			GHOST,
			/**
			 * This policy states that the field should only grow.
			 * If the value has been saved with a size that requires 8 bytes and on the
			 * subsequent writing, it requires only 1, it will still be written as if it needs 8 bytes.
			 * This can increase the predictability of where data is and may decrease fragmentation
			 * at the cost of space efficiency.
			 */
			GROW_ONLY,
			/**
			 * Rigid initial states that once a value has been written, its size is locked in. It may not grow or shrink.
			 * A field will fail to write and cause an exception if its size can not be stored due to insufficient bytes
			 * allocated to it.
			 */
			RIGID_INITIAL
		}
		
		String name() default "";
		
		NumberSize min() default NumberSize.VOID;
		NumberSize max() default NumberSize.LONG;
		
		RetentionPolicy retention() default GHOST;
		
	}
	
}
