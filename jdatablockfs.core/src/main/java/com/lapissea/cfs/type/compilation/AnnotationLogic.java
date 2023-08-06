package com.lapissea.cfs.type.compilation;

import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.VirtualFieldDefinition;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.util.NotNull;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public interface AnnotationLogic<Ann extends Annotation>{
	
	@NotNull
	default Set<String> getDependencyValueNames(FieldAccessor<?> field, Ann annotation){
		return injectPerInstanceValue(field, annotation).stream().map(VirtualFieldDefinition::name).collect(Collectors.toSet());
	}
	
	default void validate(FieldAccessor<?> field, Ann annotation){ }
	
	@NotNull
	default <T extends IOInstance<T>> List<VirtualFieldDefinition<T, ?>> injectPerInstanceValue(FieldAccessor<T> field, Ann annotation){ return List.of(); }
	
}
