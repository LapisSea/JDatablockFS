package com.lapissea.cfs.type.field.annotations;

import com.lapissea.cfs.exceptions.MalformedStructLayout;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.compilation.AnnotationLogic;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.VirtualFieldDefinition;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.util.NotNull;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static com.lapissea.cfs.type.field.IOField.UsageHintType.SIZE_DATA;
import static com.lapissea.cfs.type.field.VirtualFieldDefinition.StoragePool.INSTANCE;
import static com.lapissea.cfs.type.field.VirtualFieldDefinition.StoragePool.IO;
import static com.lapissea.cfs.type.field.annotations.IODependency.VirtualNumSize.RetentionPolicy.GHOST;

@Retention(RetentionPolicy.RUNTIME)
public @interface IODependency{
	
	AnnotationLogic<IODependency> LOGIC=new AnnotationLogic<>(){
		@Override
		public Set<String> getDependencyValueNames(FieldAccessor<?> field, IODependency annotation){
			return Set.of(annotation.value());
		}
	};
	
	String[] value();
	
	@Retention(RetentionPolicy.RUNTIME)
	@interface NumSize{
		
		AnnotationLogic<NumSize> LOGIC=new AnnotationLogic<>(){
			@NotNull
			@Override
			public Set<String> getDependencyValueNames(FieldAccessor<?> field, NumSize annotation){
				return Set.of(annotation.value());
			}
			@Override
			public <T extends IOInstance<T>> Stream<IOField.UsageHintDefinition> getHints(FieldAccessor<T> field, NumSize annotation){
				return Stream.of(new IOField.UsageHintDefinition(SIZE_DATA, annotation.value()));
			}
		};
		
		String value();
	}
	
	@Retention(RetentionPolicy.RUNTIME)
	@interface ArrayLenSize{
		
		AnnotationLogic<ArrayLenSize> LOGIC=new AnnotationLogic<>(){
			@Override
			public void validate(FieldAccessor<?> field, ArrayLenSize annotation){
				if(!field.getType().isArray()){
					throw new MalformedStructLayout(ArrayLenSize.class.getSimpleName()+" can be used only on arrays");
				}
			}
		};
		
		String name();
	}
	
	@Retention(RetentionPolicy.RUNTIME)
	@interface VirtualNumSize{
		
		enum RetentionPolicy{
			GHOST,
			GROW_ONLY,
			RIGID_INITIAL
		}
		
		class Logic implements AnnotationLogic<VirtualNumSize>{
			@NotNull
			@Override
			public Set<String> getDependencyValueNames(FieldAccessor<?> field, VirtualNumSize annotation){
				return Set.of(getName(field, annotation));
			}
			
			public static String getName(FieldAccessor<?> field, VirtualNumSize size){
				var nam=size.name();
				if(nam.isEmpty()){
					return IOFieldTools.makeNumberSizeName(field.getName());
				}
				return nam;
			}
			
			@Override
			public <T extends IOInstance<T>> Stream<IOField.UsageHintDefinition> getHints(FieldAccessor<T> field, VirtualNumSize annotation){
				return Stream.of(new IOField.UsageHintDefinition(SIZE_DATA, getName(field, annotation)));
			}
			
			@NotNull
			@Override
			public <T extends IOInstance<T>> List<VirtualFieldDefinition<T, ?>> injectPerInstanceValue(FieldAccessor<T> field, VirtualNumSize ann){
				return List.of(new VirtualFieldDefinition<>(
					ann.retention()==GHOST?IO:INSTANCE,
					getName(field, ann),
					NumberSize.class,
					new VirtualFieldDefinition.GetterFilter<T, NumberSize>(){
						private NumberSize calcMax(Struct.Pool<T> ioPool, T inst, List<FieldAccessor<T>> deps){
							return NumberSize.bySize(calcMaxVal(ioPool, inst, deps));
						}
						private long calcMaxVal(Struct.Pool<T> ioPool, T inst, List<FieldAccessor<T>> deps){
							return switch(deps.size()){
								case 1 -> deps.get(0).getLong(ioPool, inst);
								case 2 -> {
									long a=deps.get(0).getLong(ioPool, inst);
									long b=deps.get(1).getLong(ioPool, inst);
									yield Math.max(a, b);
								}
								default -> {
									long best=Long.MIN_VALUE;
									for(var d : deps){
										long newVal=d.getLong(ioPool, inst);
										if(newVal>best){
											best=newVal;
										}
									}
									yield best;
								}
							};
						}
						@Override
						public NumberSize filter(Struct.Pool<T> ioPool, T inst, List<FieldAccessor<T>> deps, NumberSize val){
							NumberSize s=(switch(ann.retention()){
								case GROW_ONLY -> {
									if(val==ann.max()) yield ann.max();
									yield calcMax(ioPool, inst, deps).max(val==null?NumberSize.VOID:val);
								}
								case RIGID_INITIAL, GHOST -> {
									yield val==null?calcMax(ioPool, inst, deps):val;
								}
							}).max(ann.min());
							
							if(s.greaterThan(ann.max())){
								throw new RuntimeException(s+" can't fit in to "+ann.max());
							}
							
							return s;
						}
					}));
			}
		}
		
		AnnotationLogic<VirtualNumSize> LOGIC=new Logic();
		
		
		String name() default "";
		
		NumberSize min() default NumberSize.VOID;
		NumberSize max() default NumberSize.LONG;
		
		RetentionPolicy retention() default GHOST;
		
	}
	
}
