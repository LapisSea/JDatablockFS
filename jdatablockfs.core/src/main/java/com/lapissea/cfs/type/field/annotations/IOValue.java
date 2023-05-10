package com.lapissea.cfs.type.field.annotations;

import com.lapissea.cfs.SyntheticParameterizedType;
import com.lapissea.cfs.exceptions.MalformedStruct;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.SupportedPrimitive;
import com.lapissea.cfs.type.compilation.AnnotationLogic;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.VirtualFieldDefinition;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.util.NotNull;
import com.lapissea.util.UtilL;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Array;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.lapissea.cfs.type.field.StoragePool.INSTANCE;
import static com.lapissea.cfs.type.field.StoragePool.IO;

/**
 * This annotation is a basic marker that a field should be saved.
 */
@SuppressWarnings("unused")
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface IOValue{
	
	AnnotationLogic<IOValue> LOGIC = new AnnotationLogic<>(){
		@NotNull
		@Override
		public <T extends IOInstance<T>> List<VirtualFieldDefinition<T, ?>> injectPerInstanceValue(FieldAccessor<T> field, IOValue annotation){
			var type   = field.getType();
			var isList = type == List.class || type == ArrayList.class;
			if(!type.isArray() && !isList) return List.of();
			if(field.hasAnnotation(Reference.class)) return List.of();
			
			var arrayLengthSizeName = field.getAnnotation(IODependency.ArrayLenSize.class)
			                               .map(IODependency.ArrayLenSize::name)
			                               .orElseGet(() -> IOFieldTools.makeNumberSizeName(IOFieldTools.makeCollectionLenName(field)));
			
			return List.of(new VirtualFieldDefinition<>(
				IO, IOFieldTools.makeCollectionLenName(field), int.class,
				(VirtualFieldDefinition.GetterFilter.I<T>)(ioPool, instance, dependencies, value) -> {
					if(value>0) return value;
					var arr = instance == null? null : field.get(ioPool, instance);
					if(arr != null){
						if(isList) return ((List<?>)arr).size();
						return Array.getLength(arr);
					}
					return 0;
				},
				List.of(
					IOFieldTools.makeAnnotation(IODependency.VirtualNumSize.class, Map.of("name", arrayLengthSizeName)),
					Unsigned.INSTANCE
				)));
		}
		@NotNull
		@Override
		public Set<String> getDependencyValueNames(FieldAccessor<?> field, IOValue annotation){
			var type = field.getType();
			if(!type.isArray() && !UtilL.instanceOf(type, Collection.class)) return Set.of();
			
			return Set.of(IOFieldTools.makeCollectionLenName(field));
		}
		@Override
		public void validate(FieldAccessor<?> field, IOValue annotation){
			try{
				var refF = field.getDeclaringStruct().getType().getDeclaredField(field.getName());
				
				if(Modifier.isStatic(refF.getModifiers())){
					throw new MalformedStruct(field + " can not be static!");
				}
				if(Modifier.isFinal(refF.getModifiers())){
					throw new MalformedStruct(field + " can not be final!");
				}
			}catch(NoSuchFieldException ignored){ }
		}
	};
	
	String name() default "";
	
	/**
	 * This annotation serves as an intent for this field to be referenced and not inlined in the object. This is useful
	 * for things such as string that have no ability to be fixed and can improve efficiency as it can allow for the
	 * holding object to be stored in a fixed form.
	 */
	@Retention(RetentionPolicy.RUNTIME)
	@Target({ElementType.FIELD, ElementType.METHOD})
	@interface Reference{
		AnnotationLogic<Reference> LOGIC = new AnnotationLogic<>(){
			@Override
			public void validate(FieldAccessor<?> field, Reference annotation){
				if(field.getType().isArray() || UtilL.instanceOf(field.getType(), List.class)){
					return;
				}
				if(!IOInstance.isInstance(field.getType())){
					if(field.hasAnnotation(Generic.class)) return;
					throw new MalformedStruct("Reference annotation can be used only in IOInstance types or collections but " + field + " is not");
				}
				if(IOInstance.isUnmanaged(field.getType())){
					throw new MalformedStruct("Reference annotation can be used only in IOInstance regular types but " + field + " is unmanaged");
				}
			}
			@NotNull
			@Override
			public <T extends IOInstance<T>> List<VirtualFieldDefinition<T, ?>> injectPerInstanceValue(FieldAccessor<T> field, Reference annotation){
				return List.of(new VirtualFieldDefinition<T, com.lapissea.cfs.objects.Reference>(
					INSTANCE,
					IOFieldTools.makeRefName(field),
					com.lapissea.cfs.objects.Reference.class,
					List.of(IOFieldTools.makeNullabilityAnn(IONullability.Mode.DEFAULT_IF_NULL))
				));
			}
			@NotNull
			@Override
			public Set<String> getDependencyValueNames(FieldAccessor<?> field, Reference annotation){
				return Set.of(IOFieldTools.makeRefName(field));
			}
		};
		
		enum PipeType{
			FLEXIBLE,
			FIXED
		}
		
		PipeType dataPipeType() default PipeType.FLEXIBLE;
	}
	
	/**
	 * This annotation provides a hint for a field about its intended type. It is particularly useful for
	 * interface types that cannot be instantiated. In cases where values need to be allocated to null
	 * fields automatically, an instantiable type is required. The annotation enables such actions by
	 * specifying the intended type of the field. For example, a field with a fictional type of FancyList (interface)
	 * may have an OverrideType annotation with a value of ActualFancyList. This tells the system to allocate an instance
	 * of ActualFancyList to the field.<br>
	 * Additionally, FancyList may be annotated with {@link DefaultImpl} to specify a default replacement type.
	 */
	@Retention(RetentionPolicy.RUNTIME)
	@Target({ElementType.FIELD, ElementType.METHOD})
	@interface OverrideType{
		
		/**
		 * Provides a default implementation for an interface.<br>
		 * This is a convenience feature that removes the requirement to always specify an {@link OverrideType} on a relevant field.
		 */
		@Retention(RetentionPolicy.RUNTIME)
		@interface DefaultImpl{
			@SuppressWarnings("rawtypes")
			Class<? extends IOInstance> value();
		}
		
		AnnotationLogic<OverrideType> LOGIC = new AnnotationLogic<>(){
			@Override
			public void validate(FieldAccessor<?> field, OverrideType typeOverride){
				Type type = field.getGenericType(null);
				
				var rawType = SyntheticParameterizedType.generalize(type);
				
				if(typeOverride.value() != Object.class){
					if(!UtilL.instanceOf(typeOverride.value(), rawType.getRawType())){
						throw new MalformedStruct(typeOverride.value().getName() + " is not a valid override of " + rawType.getRawType().getName() + " on field " + field.getName());
					}
				}
				
				var overridingArgs = typeOverride.genericArgs();
				if(overridingArgs.length != 0){
					if(rawType.getActualTypeArgumentCount() != overridingArgs.length) throw new MalformedStruct(
						field.getName() + " " + IOValue.OverrideType.class.getSimpleName() +
						" generic arguments do not match the count of " + rawType.getActualTypeArgumentCount());
					
					for(int i = 0; i<rawType.getActualTypeArgumentCount(); i++){
						var parmType       = (Class<?>)rawType.getActualTypeArgument(i);
						var overridingType = (Class<?>)overridingArgs[i];
						if(!UtilL.instanceOf(overridingType, parmType)){
							throw new MalformedStruct("Parm " + overridingType.getName() + " @" + i + " is not a valid override of " + parmType.getName() + " on field " + field.getName());
						}
					}
				}
			}
		};
		
		Class<?> value() default Object.class;
		Class<?>[] genericArgs() default {};
	}
	
	/**
	 * This annotation specifies that a number may not store negative values. This is useful for things such as sizes/lengths.
	 * Larger values may take less space with this annotation. For example, the maximum positive value of a signed 1-byte integer is 127,
	 * but an unsigned version may store a value of up to 255.
	 */
	@Retention(RetentionPolicy.RUNTIME)
	@Target({ElementType.FIELD, ElementType.METHOD})
	@interface Unsigned{
		
		Unsigned INSTANCE = IOFieldTools.makeAnnotation(Unsigned.class);
		
		AnnotationLogic<Unsigned> LOGIC = new AnnotationLogic<>(){
			@Override
			public void validate(FieldAccessor<?> field, Unsigned typeOverride){
				switch(SupportedPrimitive.get(field.getType()).orElse(null)){
					case null, BOOLEAN, FLOAT, DOUBLE, CHAR -> throw new MalformedStruct(field + " can not be unsigned");
					case BYTE, SHORT, INT, LONG -> { }
				}
			}
		};
		
		
	}
	
	/**
	 * <p>
	 * Allows an IOValue annotated field to have an unspecified value type. For example, field <code>Shape shape;</code> would
	 * normally be able to only store an object of type <code>Shape</code> but with this annotation, values of
	 * <code>Square, Circle, ...</code> are valid.
	 * </p>
	 * <p>
	 * This flexibility comes at the cost of needing to store an ID of the exact type and querying the type database.
	 * On fixed layouts, this will take up 4 bytes per field. On dynamic, it may use from 0 to 4 bytes + 3 bits in the size flag.
	 * </p>
	 */
	@Retention(RetentionPolicy.RUNTIME)
	@Target({ElementType.FIELD, ElementType.METHOD})
	@interface Generic{
		
		AnnotationLogic<Generic> LOGIC = new AnnotationLogic<>(){
			
			@NotNull
			@Override
			public <T extends IOInstance<T>> List<VirtualFieldDefinition<T, ?>> injectPerInstanceValue(FieldAccessor<T> field, Generic annotation){
				return List.of(new VirtualFieldDefinition<T, Integer>(
					IO,
					IOFieldTools.makeGenericIDFieldName(field),
					int.class,
					List.of(IOFieldTools.makeAnnotation(IODependency.VirtualNumSize.class, Map.of()), Unsigned.INSTANCE)
				));
			}
			@NotNull
			@Override
			public Set<String> getDependencyValueNames(FieldAccessor<?> field, Generic annotation){
				return Set.of(IOFieldTools.makeGenericIDFieldName(field));
			}
		};
		
	}
}
