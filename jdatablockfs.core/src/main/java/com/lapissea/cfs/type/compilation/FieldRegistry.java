package com.lapissea.cfs.type.compilation;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.objects.INumber;
import com.lapissea.cfs.type.*;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.cfs.type.field.annotations.IODynamic;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.cfs.type.field.fields.reflection.*;
import com.lapissea.util.NotImplementedException;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

class FieldRegistry{
	static CompletableFuture<RegistryNode.FieldRegistry> make(){
		return CompletableFuture.supplyAsync(()->{
			var reg=new RegistryNode.FieldRegistry();
			reg.register(new RegistryNode(){
				@Override
				public boolean canCreate(Type type, GetAnnotation annotations){
					return annotations.isPresent(IODynamic.class);
				}
				@Override
				public <T extends IOInstance<T>> IOField<T, ?> create(FieldAccessor<T> field, GenericContext genericContext){
					if(field.hasAnnotation(IOValue.Reference.class)){
						throw new NotImplementedException();
					}
					return new IOFieldDynamicInlineObject<>(field);
				}
			});
			reg.register(new RegistryNode(){
				@Override
				public boolean canCreate(Type type, GetAnnotation annotations){
					return SupportedPrimitive.isAny(type);
				}
				@Override
				public <T extends IOInstance<T>> IOField<T, ?> create(FieldAccessor<T> field, GenericContext genericContext){
					return IOFieldPrimitive.make(field);
				}
			});
			reg.register(new RegistryNode.InstanceOf<>(Enum.class){
				@Override
				public <T extends IOInstance<T>> IOField<T, Enum> create(FieldAccessor<T> field, GenericContext genericContext){
					return new IOFieldEnum<>(field);
				}
			});
			reg.register(new RegistryNode.InstanceOf<>(INumber.class){
				@Override
				public <T extends IOInstance<T>> IOField<T, INumber> create(FieldAccessor<T> field, GenericContext genericContext){
					return new IOFieldNumber<>(field);
				}
			});
			reg.register(new RegistryNode.InstanceOf<>(byte[].class){
				@Override
				public <T extends IOInstance<T>> IOField<T, byte[]> create(FieldAccessor<T> field, GenericContext genericContext){
					return new IOFieldByteArray<>(field);
				}
			});
			reg.register(new RegistryNode.InstanceOf<>(boolean[].class){
				@Override
				public <T extends IOInstance<T>> IOField<T, boolean[]> create(FieldAccessor<T> field, GenericContext genericContext){
					return new IOFieldBooleanArray<>(field);
				}
			});
			reg.register(new RegistryNode.InstanceOf<>(float[].class){
				@Override
				public <T extends IOInstance<T>> IOField<T, float[]> create(FieldAccessor<T> field, GenericContext genericContext){
					return new IOFieldFloatArray<>(field);
				}
			});
			reg.register(new RegistryNode(){
				@Override
				public boolean canCreate(Type type, GetAnnotation annotations){
					var raw=Utils.typeToRaw(type);
					if(!raw.isArray()) return false;
					return IOInstance.isManaged(raw.componentType());
				}
				@Override
				public <T extends IOInstance<T>> IOField<T, ?> create(FieldAccessor<T> field, GenericContext genericContext){
					if(field.hasAnnotation(IOValue.Reference.class)){
						return new InstanceCollection.ReferenceField<>(field, InstanceCollection.DataAdapter.ArrayAdapter.class);
					}
					return new InstanceCollection.InlineField<>(field, InstanceCollection.DataAdapter.ArrayAdapter.class);
				}
			});
			reg.register(new RegistryNode(){
				@Override
				public boolean canCreate(Type type, GetAnnotation annotations){
					if(!(type instanceof ParameterizedType parmType)) return false;
					if(parmType.getRawType()!=List.class&&parmType.getRawType()!=ArrayList.class) return false;
					var args=parmType.getActualTypeArguments();
					return IOInstance.isManaged(Objects.requireNonNull(TypeLink.of(args[0])).getTypeClass(null));
				}
				@Override
				public <T extends IOInstance<T>> IOField<T, ?> create(FieldAccessor<T> field, GenericContext genericContext){
					if(field.hasAnnotation(IOValue.Reference.class)){
						return new InstanceCollection.ReferenceField<>(field, InstanceCollection.DataAdapter.ListAdapter.class);
					}
					return new InstanceCollection.InlineField<>(field, InstanceCollection.DataAdapter.ListAdapter.class);
				}
			});
			reg.register(new RegistryNode.InstanceOf<>(String.class){
				@Override
				public <T extends IOInstance<T>> IOField<T, String> create(FieldAccessor<T> field, GenericContext genericContext){
					return new IOFieldInlineString<>(field);
				}
			});
			reg.register(new RegistryNode.InstanceOf<>(String[].class){
				@Override
				public <T extends IOInstance<T>> IOField<T, String[]> create(FieldAccessor<T> field, GenericContext genericContext){
					return new IOFieldStringArray<>(field);
				}
			});
			reg.register(new RegistryNode(){
				@Override
				public boolean canCreate(Type type, GetAnnotation annotations){
					if(!(type instanceof ParameterizedType parmType)) return false;
					if(parmType.getRawType()!=List.class&&parmType.getRawType()!=ArrayList.class) return false;
					var args=parmType.getActualTypeArguments();
					return Utils.typeToRaw(args[0])==String.class;
				}
				@Override
				public <T extends IOInstance<T>> IOField<T, ?> create(FieldAccessor<T> field, GenericContext genericContext){
					return new IOFieldStringList<>(field);
				}
			});
			reg.register(new RegistryNode.InstanceOf<>(IOInstance.class){
				@Override
				public <T extends IOInstance<T>> IOField<T, ? extends IOInstance> create(FieldAccessor<T> field, GenericContext genericContext){
					Class<?> raw      =field.getType();
					var      unmanaged=!IOInstance.isManaged(raw);
					
					if(unmanaged){
						return new IOFieldUnmanagedObjectReference<>(field);
					}
					if(field.hasAnnotation(IOValue.Reference.class)){
						return new IOFieldObjectReference<>(field);
					}
					return new IOFieldInlineObject<>(field);
				}
			});
			
			reg.register(new RegistryNode(){
				@Override
				public boolean canCreate(Type type, GetAnnotation annotations){
					var raw=Utils.typeToRaw(type);
					if(!raw.isArray()) return false;
					return raw.componentType().isEnum();
				}
				@Override
				public <T extends IOInstance<T>> IOField<T, ?> create(FieldAccessor<T> field, GenericContext genericContext){
					return new IOFieldEnumArray<>(field);
				}
			});
			reg.register(new RegistryNode(){
				@Override
				public boolean canCreate(Type type, GetAnnotation annotations){
					if(!(type instanceof ParameterizedType parmType)) return false;
					if(parmType.getRawType()!=List.class&&parmType.getRawType()!=ArrayList.class) return false;
					var args=parmType.getActualTypeArguments();
					return Utils.typeToRaw(args[0]).isEnum();
				}
				@Override
				public <T extends IOInstance<T>> IOField<T, ?> create(FieldAccessor<T> field, GenericContext genericContext){
					return new IOFieldEnumList<>(field);
				}
			});
			return reg;
		});
	}
}
