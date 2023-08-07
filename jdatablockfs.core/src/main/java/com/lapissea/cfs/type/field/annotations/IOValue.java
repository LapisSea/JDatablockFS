package com.lapissea.cfs.type.field.annotations;

import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.IOFieldTools;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * This annotation is a basic marker that a field should be saved.<br/>
 * It can be put on a class to signify that every field inside should be an io value
 */
@SuppressWarnings("unused")
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.TYPE})
public @interface IOValue{
	
	String name() default "";
	
	/**
	 * This annotation serves as an intent for this field to be referenced and not inlined in the object. This is useful
	 * for things such as string that have no ability to be fixed and can improve efficiency as it can allow for the
	 * holding object to be stored in a fixed form.
	 */
	@Retention(RetentionPolicy.RUNTIME)
	@Target({ElementType.FIELD, ElementType.METHOD})
	@interface Reference{
		
		enum PipeType{
			FLEXIBLE,
			FIXED
		}
		
		PipeType dataPipeType() default PipeType.FLEXIBLE;
	}
	
	/**
	 * This annotation provides a hint for a field about its intended type. It is particularly useful for
	 * interface types that cannot be instantiated. In cases where values need to be allocated to null
	 * fields automatically, an instantiable type is required. The annotation enables such actions by
	 * specifying the intended type of the field. For example, a field with a fictional type of FancyList (interface)
	 * may have an OverrideType annotation with a value of ActualFancyList. This tells the system to allocate an instance
	 * of ActualFancyList to the field.<br>
	 * Additionally, FancyList may be annotated with {@link DefaultImpl} to specify a default replacement type.
	 */
	@Retention(RetentionPolicy.RUNTIME)
	@Target({ElementType.FIELD, ElementType.METHOD})
	@interface OverrideType{
		
		/**
		 * Provides a default implementation for an interface.<br>
		 * This is a convenience feature that removes the requirement to always specify an {@link OverrideType} on a relevant field.
		 */
		@Retention(RetentionPolicy.RUNTIME)
		@Target({ElementType.TYPE})
		@interface DefaultImpl{
			@SuppressWarnings("rawtypes")
			Class<? extends IOInstance> value();
		}
		
		Class<?> value() default Object.class;
		Class<?>[] genericArgs() default {};
	}
	
	/**
	 * This annotation specifies that a number may not store negative values. This is useful for things such as sizes/lengths.
	 * Larger values may take less space with this annotation. For example, the maximum positive value of a signed 1-byte integer is 127,
	 * but an unsigned version may store a value of up to 255.
	 */
	@Retention(RetentionPolicy.RUNTIME)
	@Target({ElementType.FIELD, ElementType.METHOD})
	@interface Unsigned{
		Unsigned INSTANCE = IOFieldTools.makeAnnotation(Unsigned.class);
	}
	
	/**
	 * <p>
	 * Allows an IOValue annotated field to have an unspecified value type. For example, field <code>Shape shape;</code> would
	 * normally be able to only store an object of type <code>Shape</code> but with this annotation, values of
	 * <code>Square, Circle, ...</code> are valid.
	 * </p>
	 * <p>
	 * This flexibility comes at the cost of needing to store an ID of the exact type and querying the type database.
	 * On fixed layouts, this will take up 4 bytes per field. On dynamic, it may use from 0 to 4 bytes + 3 bits in the size flag.
	 * </p>
	 */
	@Retention(RetentionPolicy.RUNTIME)
	@Target({ElementType.FIELD, ElementType.METHOD})
	@interface Generic{ }
}
