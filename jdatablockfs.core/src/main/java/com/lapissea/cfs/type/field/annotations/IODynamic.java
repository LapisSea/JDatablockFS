package com.lapissea.cfs.type.field.annotations;

import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.compilation.AnnotationLogic;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.StoragePool;
import com.lapissea.cfs.type.field.VirtualFieldDefinition;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.util.NotNull;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface IODynamic{
	
	AnnotationLogic<IODynamic> LOGIC = new AnnotationLogic<>(){
		
		@NotNull
		@Override
		public <T extends IOInstance<T>> List<VirtualFieldDefinition<T, ?>> injectPerInstanceValue(FieldAccessor<T> field, IODynamic annotation){
			return List.of(new VirtualFieldDefinition<T, Integer>(
				StoragePool.IO,
				IOFieldTools.makeGenericIDFieldName(field),
				int.class,
				List.of(IOFieldTools.makeAnnotation(IODependency.VirtualNumSize.class, Map.of()), IOValue.Unsigned.INSTANCE)
			));
		}
		@NotNull
		@Override
		public Set<String> getDependencyValueNames(FieldAccessor<?> field, IODynamic annotation){
			return Set.of(IOFieldTools.makeGenericIDFieldName(field));
		}
	};
	
}
