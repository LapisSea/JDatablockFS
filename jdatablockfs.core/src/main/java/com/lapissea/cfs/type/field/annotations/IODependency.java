package com.lapissea.cfs.type.field.annotations;

import com.lapissea.cfs.exceptions.MalformedStruct;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.VarPool;
import com.lapissea.cfs.type.compilation.AnnotationLogic;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.VirtualFieldDefinition;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.util.NotNull;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Set;

import static com.lapissea.cfs.type.field.StoragePool.INSTANCE;
import static com.lapissea.cfs.type.field.StoragePool.IO;
import static com.lapissea.cfs.type.field.annotations.IODependency.VirtualNumSize.RetentionPolicy.GHOST;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface IODependency{
	
	AnnotationLogic<IODependency> LOGIC=new AnnotationLogic<>(){
		@NotNull
		@Override
		public Set<String> getDependencyValueNames(FieldAccessor<?> field, IODependency annotation){
			return Set.of(annotation.value());
		}
	};
	
	String[] value();
	
	@Retention(RetentionPolicy.RUNTIME)
	@Target({ElementType.FIELD, ElementType.METHOD})
	@interface NumSize{
		
		AnnotationLogic<NumSize> LOGIC=new AnnotationLogic<>(){
			@NotNull
			@Override
			public Set<String> getDependencyValueNames(FieldAccessor<?> field, NumSize annotation){
				return Set.of(annotation.value());
			}
		};
		
		String value();
	}
	
	@Retention(RetentionPolicy.RUNTIME)
	@Target({ElementType.FIELD, ElementType.METHOD})
	@interface ArrayLenSize{
		
		AnnotationLogic<ArrayLenSize> LOGIC=new AnnotationLogic<>(){
			@Override
			public void validate(FieldAccessor<?> field, ArrayLenSize annotation){
				if(!field.getType().isArray()){
					throw new MalformedStruct(ArrayLenSize.class.getSimpleName()+" can be used only on arrays");
				}
			}
		};
		
		String name();
	}
	
	@Retention(RetentionPolicy.RUNTIME)
	@Target({ElementType.FIELD, ElementType.METHOD})
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
			
			@NotNull
			@Override
			public <T extends IOInstance<T>> List<VirtualFieldDefinition<T, ?>> injectPerInstanceValue(FieldAccessor<T> field, VirtualNumSize ann){
				var unsigned=field.hasAnnotation(IOValue.Unsigned.class);
				return List.of(new VirtualFieldDefinition<>(
					ann.retention()==GHOST?IO:INSTANCE,
					getName(field, ann),
					NumberSize.class,
					new VirtualFieldDefinition.GetterFilter<T, NumberSize>(){
						private NumberSize calcMax(VarPool<T> ioPool, T inst, List<FieldAccessor<T>> deps){
							var len=calcMaxVal(ioPool, inst, deps);
							if(len<0){
								if(unsigned) return NumberSize.VOID;
								len*=-1;
							}
							return NumberSize.bySize(len);
						}
						private long calcMaxVal(VarPool<T> ioPool, T inst, List<FieldAccessor<T>> deps){
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
						public NumberSize filter(VarPool<T> ioPool, T inst, List<FieldAccessor<T>> deps, NumberSize val){
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
