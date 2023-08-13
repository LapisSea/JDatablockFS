package com.lapissea.cfs.type.field.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


/**
 * Moves the value in to an immutable pool and is saved by its ID. Useful for saving space on many repeating values
 */
@AnnotationUsage("Moves the value in to an immutable pool and is saved by its ID. To be used on known object types or strings")
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface IOIndexed{ }
