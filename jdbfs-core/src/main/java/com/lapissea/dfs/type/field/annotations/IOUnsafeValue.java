package com.lapissea.dfs.type.field.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * This annotation is just a flag that serves as an acknowledgement that the field is unsafe and may cause problems.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface IOUnsafeValue{
	/**
	 * Used to notate that an IOField implementation is unsafe
	 */
	@Retention(RetentionPolicy.RUNTIME)
	@Target({ElementType.TYPE})
	@interface Mark{ }
}
