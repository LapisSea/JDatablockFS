package com.lapissea.cfs.type.compilation;

import com.lapissea.cfs.IterablePP;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.VirtualFieldDefinition;
import com.lapissea.cfs.type.field.access.IFieldAccessor;
import com.lapissea.util.NotNull;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Set;

public interface AnnotationLogic<Ann extends Annotation>{
	
	record TypeData<T extends IOInstance<T>>(Class<T> type, IterablePP<IFieldAccessor<T>> ioFields){}
	
	record Context<T extends IOInstance<T>>(IFieldAccessor<T> field, TypeData<T> data){}
	
	@NotNull
	default Set<String> getDependencyValueNames(Ann annotation){return Set.of();}
	
	default void validate(Context<?> context, Ann annotation){}
	
	@NotNull
	default <T extends IOInstance<T>> List<VirtualFieldDefinition<T, ?>> injectPerInstanceValue(Context<T> context, Ann annotation){return List.of();}
	
}
