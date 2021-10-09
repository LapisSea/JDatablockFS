package com.lapissea.cfs.type.field.annotations;

import com.lapissea.cfs.type.compilation.AnnotationLogic;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface IOType{
	
	@Retention(RetentionPolicy.RUNTIME)
	@interface Dynamic{
		
		AnnotationLogic<Dynamic> LOGIC=new AnnotationLogic<>(){
		};
		
	}
	
	
}
