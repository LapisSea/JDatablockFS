package com.lapissea.cfs.type.compilation;

import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.VirtualFieldDefinition;
import com.lapissea.cfs.type.field.access.IFieldAccessor;
import com.lapissea.util.NotNull;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

public interface AnnotationLogic<Ann extends Annotation>{
	
	@NotNull
	default Set<String> getDependencyValueNames(IFieldAccessor<?> field, Ann annotation){return Set.of();}
	
	default void validate(IFieldAccessor<?> field, Ann annotation){}
	
	@NotNull
	default <T extends IOInstance<T>> List<VirtualFieldDefinition<T, ?>> injectPerInstanceValue(IFieldAccessor<T> field, Ann annotation){return List.of();}
	
	default <T extends IOInstance<T>> Stream<IOField.UsageHint> getHints(IFieldAccessor<T> field, Ann annotation){return Stream.empty();}
	
}
