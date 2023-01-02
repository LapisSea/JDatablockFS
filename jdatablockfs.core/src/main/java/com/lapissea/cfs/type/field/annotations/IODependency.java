package com.lapissea.cfs.type.field.annotations;

import com.lapissea.cfs.exceptions.MalformedStruct;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.VarPool;
import com.lapissea.cfs.type.compilation.AnnotationLogic;
import com.lapissea.cfs.type.field.IOField;
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

/**
 * This annotation can specify that other field or fields are dependent on it. This is needed when
 * a custom getter is used and is accessing another IO field.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface IODependency{
	
	AnnotationLogic<IODependency> LOGIC = new AnnotationLogic<>(){
		@NotNull
		@Override
		public Set<String> getDependencyValueNames(FieldAccessor<?> field, IODependency annotation){
			return Set.of(annotation.value());
		}
	};
	
	String[] value();
	
	/**
	 * This annotation enables a number to have a dynamic size with a specified name.
	 * <p>
	 * Multiple fields can have a size of the same name. This will make them share the dynamic size and take the form of the
	 * largest needed size. This can improve performance and efficiency when there are multiple fields of similar values
	 * </p>
	 */
	@Retention(RetentionPolicy.RUNTIME)
	@Target({ElementType.FIELD, ElementType.METHOD})
	@interface NumSize{
		
		AnnotationLogic<NumSize> LOGIC = new AnnotationLogic<>(){
			@NotNull
			@Override
			public Set<String> getDependencyValueNames(FieldAccessor<?> field, NumSize annotation){
				return Set.of(annotation.value());
			}
		};
		
		String value();
	}
	
	/**
	 * This annotation enables an array length value to have a dynamic size with a specified name
	 * <p>
	 * Multiple fields can have a size of the same name. This will make them share the dynamic size and take the form of the
	 * largest needed size. This can improve performance and efficiency when there are multiple fields of similar values
	 * </p>
	 */
	@Retention(RetentionPolicy.RUNTIME)
	@Target({ElementType.FIELD, ElementType.METHOD})
	@interface ArrayLenSize{
		
		AnnotationLogic<ArrayLenSize> LOGIC = new AnnotationLogic<>(){
			@Override
			public void validate(FieldAccessor<?> field, ArrayLenSize annotation){
				if(!field.getType().isArray()){
					throw new MalformedStruct(ArrayLenSize.class.getSimpleName() + " can be used only on arrays");
				}
			}
		};
		
		String name();
	}
	
	/**
	 * This annotation adds a virtual size field of {@link NumberSize} type. The value if this field is automatically
	 * managed. It can be modified manually through the {@link IOField} api, but it is preferred to simply specify its
	 * behaviour through the values of this annotation.
	 */
	@Retention(RetentionPolicy.RUNTIME)
	@Target({ElementType.FIELD, ElementType.METHOD})
	@interface VirtualNumSize{
		
		/**
		 * This enum specifies the logic that determines the behaviour of the automatic computation of the virtual field value.
		 */
		enum RetentionPolicy{
			/**
			 * This policy states that a field should simply conform to the minimum valid size of the number. This value may grow
			 * or shrink without any restriction. This is the default and preferred mode.
			 */
			GHOST,
			/**
			 * This policy states that a field should only grow. If the value has been saved with a size that requires 8 bytes and
			 * on the subsequent write it requires only 1, it will still be written as if it needs 8 bytes. This can increase the
			 * predictability of where data is and may decrease fragmentation at the cost of space efficiency.
			 */
			GROW_ONLY,
			/**
			 * Rigid initial states that once a value has been written, its size is locked in. It may not grow or shrink.
			 * A field will fail to write and cause an exception if its size can not be stored due to insufficient bytes
			 * allocated to it.
			 */
			RIGID_INITIAL
		}
		
		class Logic implements AnnotationLogic<VirtualNumSize>{
			@NotNull
			@Override
			public Set<String> getDependencyValueNames(FieldAccessor<?> field, VirtualNumSize annotation){
				return Set.of(getName(field, annotation));
			}
			
			public static String getName(FieldAccessor<?> field, VirtualNumSize size){
				var nam = size.name();
				if(nam.isEmpty()){
					return IOFieldTools.makeNumberSizeName(field);
				}
				return nam;
			}
			
			@NotNull
			@Override
			public <T extends IOInstance<T>> List<VirtualFieldDefinition<T, ?>> injectPerInstanceValue(FieldAccessor<T> field, VirtualNumSize ann){
				var unsigned = field.hasAnnotation(IOValue.Unsigned.class);
				return List.of(new VirtualFieldDefinition<>(
					ann.retention() == GHOST? IO : INSTANCE,
					getName(field, ann),
					NumberSize.class,
					new VirtualFieldDefinition.GetterFilter<T, NumberSize>(){
						private NumberSize calcMax(VarPool<T> ioPool, T inst, List<FieldAccessor<T>> deps){
							var len = calcMaxVal(ioPool, inst, deps);
							if(len<0){
								if(unsigned) return NumberSize.VOID;
								len *= -1;
							}
							return NumberSize.bySize(len);
						}
						private long calcMaxVal(VarPool<T> ioPool, T inst, List<FieldAccessor<T>> deps){
							return switch(deps.size()){
								case 1 -> deps.get(0).getLong(ioPool, inst);
								case 2 -> {
									long a = deps.get(0).getLong(ioPool, inst);
									long b = deps.get(1).getLong(ioPool, inst);
									yield Math.max(a, b);
								}
								default -> {
									long best = Long.MIN_VALUE;
									for(var d : deps){
										long newVal = d.getLong(ioPool, inst);
										if(newVal>best){
											best = newVal;
										}
									}
									yield best;
								}
							};
						}
						@Override
						public NumberSize filter(VarPool<T> ioPool, T inst, List<FieldAccessor<T>> deps, NumberSize val){
							NumberSize s = (switch(ann.retention()){
								case GROW_ONLY -> {
									if(val == ann.max()) yield ann.max();
									yield calcMax(ioPool, inst, deps).max(val == null? NumberSize.VOID : val);
								}
								case RIGID_INITIAL, GHOST -> {
									yield val == null? calcMax(ioPool, inst, deps) : val;
								}
							}).max(ann.min());
							
							if(s.greaterThan(ann.max())){
								throw new RuntimeException(s + " can't fit in to " + ann.max());
							}
							
							return s;
						}
					}));
			}
		}
		
		AnnotationLogic<VirtualNumSize> LOGIC = new Logic();
		
		
		String name() default "";
		
		NumberSize min() default NumberSize.VOID;
		NumberSize max() default NumberSize.LONG;
		
		RetentionPolicy retention() default GHOST;
		
	}
	
}
