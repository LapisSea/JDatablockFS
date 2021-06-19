package com.lapissea.cfs.type.field.annotations;

import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.compilation.AnnotationLogic;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.VirtualFieldDefinition;
import com.lapissea.cfs.type.field.access.IFieldAccessor;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static com.lapissea.cfs.type.field.IOField.UsageHintType.*;
import static com.lapissea.cfs.type.field.annotations.IODependency.VirtualNumSize.RetentionPolicy.*;

@Retention(RetentionPolicy.RUNTIME)
public @interface IODependency{
	
	AnnotationLogic<IODependency> LOGIC=new AnnotationLogic<>(){
		@Override
		public Set<String> getDependencyValueNames(IFieldAccessor<?> field, IODependency annotation){
			return Set.of(annotation.value());
		}
	};
	
	String[] value();
	
	@Retention(RetentionPolicy.RUNTIME)
	@interface NumSize{
		
		AnnotationLogic<NumSize> LOGIC=new AnnotationLogic<>(){
			@Override
			public Set<String> getDependencyValueNames(IFieldAccessor<?> field, NumSize annotation){
				return Set.of(annotation.value());
			}
			@Override
			public Stream<IOField.UsageHint> getHints(NumSize annotation){
				return Stream.of(new IOField.UsageHint(DYNAMIC_SIZE_RESOLVE_DATA, annotation.value()));
			}
		};
		
		String value();
	}
	
	@Retention(RetentionPolicy.RUNTIME)
	@interface VirtualNumSize{
		
		enum RetentionPolicy{
			GHOST,
			GROW_ONLY,
			RIGID_INITIAL
		}
		
		AnnotationLogic<VirtualNumSize> LOGIC=new AnnotationLogic<>(){
			@Override
			public Set<String> getDependencyValueNames(IFieldAccessor<?> field, VirtualNumSize annotation){
				return Set.of(annotation.name());
			}
			
			@Override
			public Stream<IOField.UsageHint> getHints(VirtualNumSize annotation){
				return Stream.of(new IOField.UsageHint(DYNAMIC_SIZE_RESOLVE_DATA, annotation.name()));
			}
			
			@Override
			public <T extends IOInstance<T>> List<VirtualFieldDefinition<T, ?>> injectPerInstanceValue(IFieldAccessor<T> field, VirtualNumSize ann){
				return List.of(new VirtualFieldDefinition<>(
					ann.retention()!=GHOST,
					ann.name(),
					NumberSize.class,
					new VirtualFieldDefinition.GetterFilter<T, NumberSize>(){
						private NumberSize calcMax(T inst, List<IFieldAccessor<T>> deps){
							return NumberSize.bySize(deps.stream().mapToLong(d->d.getLong(inst)).max().orElse(0L));
						}
						@Override
						public NumberSize filter(T inst, List<IFieldAccessor<T>> deps, NumberSize val){
							NumberSize s=(switch(ann.retention()){
								case GHOST -> calcMax(inst, deps);
								case GROW_ONLY -> {
									if(val==ann.max()) yield ann.max();
									yield calcMax(inst, deps).max(val==null?NumberSize.VOID:val);
								}
								case RIGID_INITIAL -> val==null?calcMax(inst, deps):val;
							}).max(ann.min());
							
							if(s.greaterThan(ann.max())){
								throw new RuntimeException(s+" can't fit in to "+ann.max());
							}
							
							return s;
						}
					}));
			}
		};
		
		String name();
		
		NumberSize min() default NumberSize.VOID;
		NumberSize max() default NumberSize.LONG;
		
		RetentionPolicy retention() default GHOST;
		
	}
	
}
