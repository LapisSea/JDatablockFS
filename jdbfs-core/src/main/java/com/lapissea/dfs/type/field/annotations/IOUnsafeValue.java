package com.lapissea.dfs.type.field.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * This annotation is just a flag that serves as an acknowledgement that the field is unsafe and may cause problems.
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface IOUnsafeValue{ }
