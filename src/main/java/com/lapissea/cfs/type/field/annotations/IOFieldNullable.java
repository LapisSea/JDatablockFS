package com.lapissea.cfs.type.field.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface IOFieldNullable{
	boolean value() default false;
}
