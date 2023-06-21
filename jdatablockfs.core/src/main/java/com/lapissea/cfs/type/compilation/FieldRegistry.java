package com.lapissea.cfs.type.compilation;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.internal.Runner;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.GetAnnotation;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.SupportedPrimitive;
import com.lapissea.cfs.type.TypeLink;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.cfs.type.field.fields.CollectionAddapter;
import com.lapissea.cfs.type.field.fields.reflection.IOFieldBooleanArray;
import com.lapissea.cfs.type.field.fields.reflection.IOFieldByteArray;
import com.lapissea.cfs.type.field.fields.reflection.IOFieldChunkPointer;
import com.lapissea.cfs.type.field.fields.reflection.IOFieldDynamicInlineObject;
import com.lapissea.cfs.type.field.fields.reflection.IOFieldDynamicReferenceObject;
import com.lapissea.cfs.type.field.fields.reflection.IOFieldEnum;
import com.lapissea.cfs.type.field.fields.reflection.IOFieldEnumArray;
import com.lapissea.cfs.type.field.fields.reflection.IOFieldEnumList;
import com.lapissea.cfs.type.field.fields.reflection.IOFieldFloatArray;
import com.lapissea.cfs.type.field.fields.reflection.IOFieldInlineObject;
import com.lapissea.cfs.type.field.fields.reflection.IOFieldObjectReference;
import com.lapissea.cfs.type.field.fields.reflection.IOFieldPrimitive;
import com.lapissea.cfs.type.field.fields.reflection.IOFieldUnmanagedObjectReference;
import com.lapissea.cfs.type.field.fields.reflection.InstanceCollection;
import com.lapissea.cfs.type.field.fields.reflection.wrappers.IOFieldInlineString;
import com.lapissea.cfs.type.field.fields.reflection.wrappers.IOFieldStringCollection;
import com.lapissea.util.LateInit;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

class FieldRegistry{
	static LateInit.Safe<RegistryNode.FieldRegistry> make(){
		return Runner.async(() -> {
			var reg = new RegistryNode.FieldRegistry();
			reg.register(new RegistryNode(){
				@Override
				public boolean canCreate(Type type, GetAnnotation annotations){
					return IOFieldTools.isGeneric(annotations);
				}
				@Override
				public <T extends IOInstance<T>> IOField<T, ?> create(FieldAccessor<T> field, GenericContext genericContext){
					if(field.hasAnnotation(IOValue.Reference.class)){
						return new IOFieldDynamicReferenceObject<>(field);
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
			reg.register(new RegistryNode.InstanceOf<>(ChunkPointer.class){
				@Override
				public <T extends IOInstance<T>> IOField<T, ChunkPointer> create(FieldAccessor<T> field, GenericContext genericContext){
					return new IOFieldChunkPointer<>(field);
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
					var raw = Utils.typeToRaw(type);
					if(!raw.isArray()) return false;
					return IOInstance.isManaged(raw.componentType());
				}
				@Override
				public <T extends IOInstance<T>> IOField<T, ?> create(FieldAccessor<T> field, GenericContext genericContext){
					if(field.hasAnnotation(IOValue.Reference.class)){
						return new InstanceCollection.ReferenceField<>(field, CollectionAddapter.OfArray.class);
					}
					return new InstanceCollection.InlineField<>(field, CollectionAddapter.OfArray.class);
				}
			});
			reg.register(new RegistryNode(){
				@Override
				public boolean canCreate(Type type, GetAnnotation annotations){
					if(!(type instanceof ParameterizedType parmType)) return false;
					if(parmType.getRawType() != List.class && parmType.getRawType() != ArrayList.class) return false;
					var args = parmType.getActualTypeArguments();
					return IOInstance.isManaged(Objects.requireNonNull(TypeLink.of(args[0])).getTypeClass(null));
				}
				@Override
				public <T extends IOInstance<T>> IOField<T, ?> create(FieldAccessor<T> field, GenericContext genericContext){
					if(field.hasAnnotation(IOValue.Reference.class)){
						return new InstanceCollection.ReferenceField<>(field, CollectionAddapter.OfList.class);
					}
					return new InstanceCollection.InlineField<>(field, CollectionAddapter.OfList.class);
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
					return new IOFieldStringCollection<>(field, CollectionAddapter.OfArray.class);
				}
			});
			reg.register(new RegistryNode(){
				@Override
				public boolean canCreate(Type type, GetAnnotation annotations){
					if(!(type instanceof ParameterizedType parmType)) return false;
					if(parmType.getRawType() != List.class && parmType.getRawType() != ArrayList.class) return false;
					var args = parmType.getActualTypeArguments();
					return Utils.typeToRaw(args[0]) == String.class;
				}
				@Override
				public <T extends IOInstance<T>> IOField<T, ?> create(FieldAccessor<T> field, GenericContext genericContext){
					return new IOFieldStringCollection<>(field, CollectionAddapter.OfList.class);
				}
			});
			reg.register(new RegistryNode.InstanceOf<>(IOInstance.class){
				@Override
				public <T extends IOInstance<T>> IOField<T, ? extends IOInstance> create(FieldAccessor<T> field, GenericContext genericContext){
					Class<?> raw       = field.getType();
					var      unmanaged = !IOInstance.isManaged(raw);
					
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
					var raw = Utils.typeToRaw(type);
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
					if(parmType.getRawType() != List.class && parmType.getRawType() != ArrayList.class) return false;
					var args = parmType.getActualTypeArguments();
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
