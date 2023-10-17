package com.lapissea.dfs.type.field.annotations;

import java.lang.annotation.Retention;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Provides a suggestion/description of where a field annotation may be used and why
 */
@Retention(RUNTIME)
public @interface AnnotationUsage{
	String value();
}
