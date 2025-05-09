package com.lapissea.dfs.type.field.fields;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.io.instancepipe.ObjectPipe;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.objects.Reference;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.field.FieldNames;
import com.lapissea.dfs.type.field.FieldSet;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.IOFieldTools;
import com.lapissea.dfs.type.field.SizeDescriptor;
import com.lapissea.dfs.type.field.access.FieldAccessor;
import com.lapissea.dfs.type.field.fields.reflection.IOFieldDynamicReferenceObject;
import com.lapissea.dfs.type.field.fields.reflection.IOFieldObjectReference;
import com.lapissea.dfs.type.field.fields.reflection.IOFieldUnmanagedObjectReference;
import com.lapissea.dfs.type.field.fields.reflection.InstanceCollection;
import com.lapissea.dfs.utils.iterableplus.Iters;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import static com.lapissea.dfs.config.GlobalConfig.DEBUG_VALIDATION;
import static com.lapissea.dfs.type.field.annotations.IONullability.Mode.DEFAULT_IF_NULL;

public abstract sealed class RefField<T extends IOInstance<T>, Type> extends IOField<T, Type>{
	
	public abstract static sealed class InstRef<T extends IOInstance<T>, Type extends IOInstance<Type>> extends RefField<T, Type> implements Inst<T, Type>
		permits NoIO, IOFieldUnmanagedObjectReference{
		public InstRef(FieldAccessor<T> accessor){
			super(accessor);
		}
		protected InstRef(FieldAccessor<T> accessor, SizeDescriptor<T> descriptor){
			super(accessor, descriptor);
		}
	}
	
	public interface Inst<T extends IOInstance<T>, Type extends IOInstance<Type>>{
		StructPipe<Type> getReferencedPipe(T instance) throws IOException;
	}
	
	public abstract static non-sealed class NoIO<T extends IOInstance<T>, ValueType extends IOInstance<ValueType>>
		extends InstRef<T, ValueType> implements DisabledIO<T>{
		
		public NoIO(FieldAccessor<T> accessor, SizeDescriptor<T> inlineSizeDescriptor){
			super(accessor, inlineSizeDescriptor);
		}
		@Override
		protected Set<TypeFlag> computeTypeFlags(){
			return Iters.of(
				IOFieldTools.isGeneric(this)? TypeFlag.DYNAMIC : null,
				TypeFlag.IO_INSTANCE
			).nonNulls().toModSet();
		}
		@Override
		public void allocate(T instance, DataProvider provider, GenericContext genericContext){
			throw new UnsupportedOperationException();
		}
	}
	
	public abstract static non-sealed class NoIOObj<T extends IOInstance<T>, ValueType>
		extends RefField<T, ValueType> implements DisabledIO<T>{
		
		public NoIOObj(FieldAccessor<T> accessor, SizeDescriptor<T> inlineSizeDescriptor){
			super(accessor, inlineSizeDescriptor);
		}
		@Override
		protected Set<TypeFlag> computeTypeFlags(){
			return IOFieldTools.isGeneric(this)? Set.of(TypeFlag.DYNAMIC) : Set.of();
		}
		
		@Override
		public void allocate(T instance, DataProvider provider, GenericContext genericContext){
			throw new UnsupportedOperationException();
		}
	}
	
	public abstract static sealed class ReferenceCompanion<T extends IOInstance<T>, ValueType> extends RefField<T, ValueType>
		permits IOFieldDynamicReferenceObject, IOFieldObjectReference, InstanceCollection.ReferenceField{
		
		private IOField<T, Reference> referenceField;
		
		public ReferenceCompanion(FieldAccessor<T> accessor){
			super(accessor);
		}
		protected ReferenceCompanion(FieldAccessor<T> accessor, SizeDescriptor<T> descriptor){
			super(accessor, descriptor);
		}
		
		@Override
		public void init(FieldSet<T> fields){
			super.init(fields);
			referenceField = getDependencies().requireExact(Reference.class, FieldNames.ref(getAccessor()));
		}
		
		protected void setRef(T instance, Reference newRef){
			referenceField.set(null, instance, newRef);
		}
		protected Reference getRef(T instance){
			return referenceField.get(null, instance);
		}
		
		@Override
		public Reference getReference(T instance){
			var ref = getRef(instance);
			if(ref.isNull()){
				return switch(getNullability()){
					case NOT_NULL -> throw new NullPointerException();
					case NULLABLE -> get(null, instance) != null? null : ref;
					case DEFAULT_IF_NULL -> null;
				};
				
			}
			return ref;
		}
		
		@Override
		public List<ValueGeneratorInfo<T, ?>> getGenerators(){
			return List.of(new ValueGeneratorInfo<>(referenceField, new ValueGenerator<>(){
				@Override
				public Strictness strictDetermineLevel(){ return Strictness.ON_EXTERNAL_ALWAYS; }
				@Override
				public boolean shouldGenerate(VarPool<T> ioPool, DataProvider provider, T instance){
					boolean refNull = switch(getNullability()){
						case NOT_NULL, DEFAULT_IF_NULL -> false;
						case NULLABLE -> {
							var val = get(ioPool, instance);
							yield val == null;
						}
					};
					
					var     ref       = getRef(instance);
					boolean isRefNull = ref == null || ref.isNull();
					
					return refNull != isRefNull;
				}
				@Override
				public Reference generate(VarPool<T> ioPool, DataProvider provider, T instance, boolean allowExternalMod) throws IOException{
					var val = get(ioPool, instance);
					
					if(val == null){
						if(allowExternalMod && getNullability() == DEFAULT_IF_NULL){
							val = newDefault();
						}else{
							return Reference.NULL;
						}
					}
					
					if(DEBUG_VALIDATION){
						var ref = getRef(instance);
						if(ref != null && !ref.isNull()) throw new IllegalStateException();
					}
					if(!allowExternalMod) throw new RuntimeException("data modification should not be done here");
					return allocNew(provider, val);
				}
			}));
		}
		
		protected abstract ValueType newDefault();
		protected abstract Reference allocNew(DataProvider provider, ValueType val) throws IOException;
	}
	
	public RefField(FieldAccessor<T> accessor){
		super(accessor);
	}
	protected RefField(FieldAccessor<T> accessor, SizeDescriptor<T> inlineSizeDescriptor){
		super(accessor, inlineSizeDescriptor);
	}
	
	public void allocateUnmanaged(T instance) throws IOException{
		IOInstance.Unmanaged<?> unmanaged = (IOInstance.Unmanaged<?>)instance;
		allocate(instance, unmanaged.getDataProvider(), unmanaged.getGenerics());
	}
	
	public abstract void allocate(T instance, DataProvider provider, GenericContext genericContext) throws IOException;
	public abstract void setReference(T instance, Reference newRef) throws IOException;
	public abstract Reference getReference(T instance) throws IOException;
	public abstract ObjectPipe<Type, ?> getReferencedPipe(T instance) throws IOException;
}
