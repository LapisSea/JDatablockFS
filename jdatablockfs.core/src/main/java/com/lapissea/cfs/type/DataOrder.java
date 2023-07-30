package com.lapissea.cfs.type;

import com.lapissea.cfs.type.compilation.TemplateClassLoader;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * For internal use only. To be used only by {@link TemplateClassLoader}
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
public @interface DataOrder{
	String[] value();
}
