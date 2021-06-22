package com.lapissea.cfs.type.field.annotations;

import com.lapissea.cfs.exceptions.MalformedStructLayout;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.compilation.AnnotationLogic;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.VirtualFieldDefinition;
import com.lapissea.cfs.type.field.access.IFieldAccessor;
import com.lapissea.util.NotNull;
import com.lapissea.util.UtilL;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Array;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.lapissea.cfs.type.field.VirtualFieldDefinition.StoragePool.*;

@Retention(RetentionPolicy.RUNTIME)
public @interface IOValue{
	
	AnnotationLogic<IOValue> LOGIC=new AnnotationLogic<>(){
		@NotNull
		@Override
		public <T extends IOInstance<T>> List<VirtualFieldDefinition<T, ?>> injectPerInstanceValue(IFieldAccessor<T> field, IOValue annotation){
			var type=field.getType();
			if(!type.isArray()) return List.of();
			
			var arrayLengthSizeName=field.getAnnotation(IODependency.ArrayLenSize.class)
			                             .map(IODependency.ArrayLenSize::name)
			                             .orElseGet(()->IOFieldTools.makeArrayLenName(field)+".nSiz");
			
			return List.of(new VirtualFieldDefinition<T, Integer>(IO, IOFieldTools.makeArrayLenName(field), Integer.class, (instance, dependencies, value)->{
				if(value!=null) return value;
				var arr=field.get(instance);
				if(arr!=null) return Array.getLength(arr);
				return -1;
			}, List.of(IOFieldTools.makeAnnotation(IODependency.VirtualNumSize.class, Map.of("name", arrayLengthSizeName)))));
		}
		@NotNull
		@Override
		public Set<String> getDependencyValueNames(IFieldAccessor<?> field, IOValue annotation){
			var type=field.getType();
			if(!type.isArray()) return Set.of();
			
			return Set.of(IOFieldTools.makeArrayLenName(field));
		}
	};
	
	String name() default "";
	
	@Retention(RetentionPolicy.RUNTIME)
	@interface Reference{
		AnnotationLogic<Reference> LOGIC=new AnnotationLogic<>(){
			@Override
			public void validate(IFieldAccessor<?> field, Reference annotation){
				if(!UtilL.instanceOf(field.getType(), IOInstance.class)){
					throw new MalformedStructLayout("Reference annotation can be used only in IOInstance types but "+field+" is not");
				}
				if(UtilL.instanceOf(field.getType(), IOInstance.Unmanaged.class)){
					throw new MalformedStructLayout("Reference annotation can be used only in IOInstance regular types but "+field+" is unmanaged");
				}
			}
			@NotNull
			@Override
			public <T extends IOInstance<T>> List<VirtualFieldDefinition<T, ?>> injectPerInstanceValue(IFieldAccessor<T> field, Reference annotation){
				return List.of(new VirtualFieldDefinition<T, com.lapissea.cfs.objects.Reference>(
					INSTANCE,
					IOFieldTools.makeRefName(field),
					com.lapissea.cfs.objects.Reference.class,
					List.of(IOFieldTools.makeAnnotation(IONullability.class, Map.of("value", IONullability.Mode.DEFAULT_IF_NULL)))
				));
			}
			@NotNull
			@Override
			public Set<String> getDependencyValueNames(IFieldAccessor<?> field, Reference annotation){
				return Set.of(IOFieldTools.makeRefName(field));
			}
		};
	}
	
	@Retention(RetentionPolicy.RUNTIME)
	@interface OverrideType{
		
		AnnotationLogic<OverrideType> LOGIC=new AnnotationLogic<>(){
			@Override
			public void validate(IFieldAccessor<?> field, OverrideType typeOverride){
				Type type=field.getGenericType();
				
				Class<?> raw;
				Type[]   parms;
				if(type instanceof ParameterizedType parmType){
					raw=(Class<?>)parmType.getRawType();
					parms=parmType.getActualTypeArguments();
				}else{
					raw=(Class<?>)type;
					parms=new Type[0];
				}
				
				if(typeOverride.value()!=Object.class){
					if(!UtilL.instanceOf(typeOverride.value(), raw)){
						throw new MalformedStructLayout(typeOverride.value().getName()+" is not a valid override of "+raw.getName()+" on field "+field.getName());
					}
				}
				
				var overridingArgs=typeOverride.genericArgs();
				if(overridingArgs.length!=0){
					if(parms.length!=overridingArgs.length) throw new MalformedStructLayout(
						field.getName()+" "+IOValue.OverrideType.class.getSimpleName()+
						" generic arguments do not match the count of "+parms.length);
					
					for(int i=0;i<parms.length;i++){
						var parmType      =(Class<?>)parms[i];
						var overridingType=(Class<?>)overridingArgs[i];
						if(!UtilL.instanceOf(overridingType, parmType)){
							throw new MalformedStructLayout("Parm "+overridingType.getName()+" @"+i+" is not a valid override of "+parmType.getName()+" on field "+field.getName());
						}
					}
				}
			}
		};
		
		Class<?> value() default Object.class;
		Class<?>[] genericArgs() default {};
	}
}
