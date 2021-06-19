package com.lapissea.cfs.type.compilation;

import com.lapissea.cfs.objects.INumber;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.access.IFieldAccessor;
import com.lapissea.cfs.type.field.annotations.IODependency;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.cfs.type.field.fields.reflection.*;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.UtilL;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Set;

public class ReflectionFieldCompiler extends FieldCompiler{
	
	
	public static final RegistryNode.Registry REGISTRY=new RegistryNode.Registry();
	
	static{
		REGISTRY.register(new RegistryNode(){
			@Override
			public boolean canCreate(Type type){
				return IOFieldPrimitive.isPrimitive(type);
			}
			@Override
			public <T extends IOInstance<T>> IOField<T, ?> create(IFieldAccessor<T> field){
				return IOFieldPrimitive.make(field);
			}
		});
		REGISTRY.register(new RegistryNode.InstanceOf(){
			@Override
			public Class<?> getType(){
				return Enum.class;
			}
			@Override
			public <T extends IOInstance<T>> IOField<T, ?> create(IFieldAccessor<T> field){
				return new IOFieldEnum<>(field);
			}
		});
		REGISTRY.register(new RegistryNode.InstanceOf(){
			@Override
			public Class<?> getType(){
				return INumber.class;
			}
			@Override
			public <T extends IOInstance<T>> IOField<T, ?> create(IFieldAccessor<T> field){
				return new IOFieldNumber<>(field);
			}
		});
		REGISTRY.register(new RegistryNode.InstanceOf(){
			@Override
			public Class<?> getType(){
				return byte[].class;
			}
			@Override
			public <T extends IOInstance<T>> IOField<T, ?> create(IFieldAccessor<T> field){
				return new IOFieldByteArray<>(field);
			}
		});
		REGISTRY.register(new RegistryNode.InstanceOf(){
			@Override
			public Class<?> getType(){
				return IOInstance.class;
			}
			@Override
			public <T extends IOInstance<T>> IOField<T, ?> create(IFieldAccessor<T> field){
				Class<?> raw=field.getType();
				if(UtilL.instanceOf(raw, IOInstance.Unmanaged.class)){
					return new IOFieldUnmanagedObjectReference<>(field);
				}
				if(field.hasAnnotation(IOValue.Reference.class)){
					throw new NotImplementedException("reference managed instance reference");
				}
				return new IOFieldInlineObject<>(field);
			}
		});
	}
	
	@Override
	protected RegistryNode.Registry registry(){
		return REGISTRY;
	}
	@Override
	protected Set<Class<? extends Annotation>> activeAnnotations(){
		return Set.of(
			IOValue.class,
			IODependency.class,
			IONullability.class
		);
	}
	
}
