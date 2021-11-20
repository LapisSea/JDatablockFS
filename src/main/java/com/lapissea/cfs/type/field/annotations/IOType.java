package com.lapissea.cfs.type.field.annotations;

import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.compilation.AnnotationLogic;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.VirtualFieldDefinition;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.util.NotNull;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Retention(RetentionPolicy.RUNTIME)
public @interface IOType{
	
	@Retention(RetentionPolicy.RUNTIME)
	@interface Dynamic{
		
		AnnotationLogic<Dynamic> LOGIC=new AnnotationLogic<>(){
			@NotNull
			@Override
			public <T extends IOInstance<T>> List<VirtualFieldDefinition<T, ?>> injectPerInstanceValue(FieldAccessor<T> field, Dynamic annotation){
				var f=new VirtualFieldDefinition<T, Integer>(
					VirtualFieldDefinition.StoragePool.INSTANCE,//FIXME: this should be changed IO once IO reading or sizing is implemented
					IOFieldTools.makeGenericIDFieldName(field),
					Integer.class,
					(instance, dependencies, value)->value==null?0:value,
					List.of(IOFieldTools.makeAnnotation(IODependency.VirtualNumSize.class, Map.of()))
				);
				return List.of(f);
			}
			@NotNull
			@Override
			public Set<String> getDependencyValueNames(FieldAccessor<?> field, Dynamic annotation){
				return Set.of(IOFieldTools.makeGenericIDFieldName(field));
			}
		};
		
	}
	
	
}
