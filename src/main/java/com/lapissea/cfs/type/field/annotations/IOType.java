package com.lapissea.cfs.type.field.annotations;

import com.lapissea.cfs.exceptions.MalformedStructLayout;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.compilation.AnnotationLogic;
import com.lapissea.cfs.type.field.access.IFieldAccessor;
import com.lapissea.util.UtilL;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface IOType{
	
	@Retention(RetentionPolicy.RUNTIME)
	@interface Dynamic{
		
		AnnotationLogic<Dynamic> LOGIC=new AnnotationLogic<>(){
			@Override
			public void validate(IFieldAccessor<?> field, Dynamic annotation){
				var typ=field.getType();
				if(!UtilL.instanceOf(typ, IOInstance.class)){
					throw new MalformedStructLayout(field+" must be an "+IOInstance.class.getSimpleName());
				}
			}
		};
		
	}
	
	
}
