package com.lapissea.dfs.type.field.annotations;

import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.utils.iterableplus.IterablePP;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface IOUnmanagedValueInfo{
	
	interface Data<T extends IOInstance.Unmanaged<T>>{
		
		IterablePP<IOField<T, ?>> getFields();
		
	}
}
