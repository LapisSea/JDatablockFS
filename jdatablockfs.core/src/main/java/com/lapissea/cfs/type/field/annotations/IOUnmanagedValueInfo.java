package com.lapissea.cfs.type.field.annotations;

import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.IOField;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.stream.Stream;


@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface IOUnmanagedValueInfo{
	
	interface Data<T extends IOInstance.Unmanaged<T>>{
		
		Stream<IOField<T, ?>> getFields();
		
	}
}
