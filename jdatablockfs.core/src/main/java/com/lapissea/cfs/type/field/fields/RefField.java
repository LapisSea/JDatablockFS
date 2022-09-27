package com.lapissea.cfs.type.field.fields;

import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.io.instancepipe.ObjectPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.VarPool;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.access.FieldAccessor;

import java.io.IOException;
import java.util.List;

import static com.lapissea.cfs.GlobalConfig.DEBUG_VALIDATION;
import static com.lapissea.cfs.type.field.annotations.IONullability.Mode.DEFAULT_IF_NULL;

public abstract class RefField<T extends IOInstance<T>, Type> extends IOField<T, Type>{
	
	public abstract static class InstRef<T extends IOInstance<T>, Type extends IOInstance<Type>> extends RefField<T, Type> implements Inst<T, Type>{
		public InstRef(FieldAccessor<T> accessor){
			super(accessor);
		}
	}
	
	public interface Inst<T extends IOInstance<T>, Type extends IOInstance<Type>>{
		StructPipe<Type> getReferencedPipe(T instance);
	}
	
	public abstract static class NoIO<T extends IOInstance<T>, ValueType extends IOInstance<ValueType>> extends InstRef<T, ValueType> implements DisabledIO<T>{
		
		private final SizeDescriptor<T> sizeDescriptor;
		
		public NoIO(FieldAccessor<T> accessor, SizeDescriptor<T> sizeDescriptor){
			super(accessor);
			this.sizeDescriptor=sizeDescriptor;
		}
		
		@Override
		public SizeDescriptor<T> getSizeDescriptor(){
			return sizeDescriptor;
		}
		
		@Override
		public void allocate(T instance, DataProvider provider, GenericContext genericContext){
			throw new UnsupportedOperationException();
		}
	}
	
	public abstract static class ReferenceCompanion<T extends IOInstance<T>, ValueType> extends RefField<T, ValueType>{
		
		private IOField<T, Reference> referenceField;
		
		public ReferenceCompanion(FieldAccessor<T> accessor){
			super(accessor);
		}
		
		@Override
		public void init(){
			super.init();
			referenceField=getDependencies().requireExact(Reference.class, IOFieldTools.makeRefName(getAccessor()));
		}
		
		protected void setRef(T instance, Reference newRef){
			referenceField.set(null, instance, newRef);
		}
		protected Reference getRef(T instance){
			return referenceField.get(null, instance);
		}
		
		@Override
		public Reference getReference(T instance){
			var ref=getRef(instance);
			if(ref.isNull()){
				return switch(getNullability()){
					case NOT_NULL -> throw new NullPointerException();
					case NULLABLE -> get(null, instance)!=null?null:ref;
					case DEFAULT_IF_NULL -> null;
				};
				
			}
			return ref;
		}
		
		@Override
		public List<ValueGeneratorInfo<T, ?>> getGenerators(){
			return List.of(new ValueGeneratorInfo<>(referenceField, new ValueGenerator<>(){
				@Override
				public boolean shouldGenerate(VarPool<T> ioPool, DataProvider provider, T instance){
					boolean refNull=switch(getNullability()){
						case NOT_NULL, DEFAULT_IF_NULL -> false;
						case NULLABLE -> {
							var val=get(ioPool, instance);
							yield val==null;
						}
					};
					
					var     ref      =getRef(instance);
					boolean isRefNull=ref==null||ref.isNull();
					
					return refNull!=isRefNull;
				}
				@Override
				public Reference generate(VarPool<T> ioPool, DataProvider provider, T instance, boolean allowExternalMod) throws IOException{
					var val=get(ioPool, instance);
					
					if(val==null){
						if(allowExternalMod&&getNullability()==DEFAULT_IF_NULL){
							val=newDefault();
						}else{
							return new Reference();
						}
					}
					
					if(DEBUG_VALIDATION){
						var ref=getRef(instance);
						if(ref!=null&&!ref.isNull()) throw new IllegalStateException();
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
	
	public void allocateUnmanaged(T instance) throws IOException{
		IOInstance.Unmanaged<?> unmanaged=(IOInstance.Unmanaged<?>)instance;
		allocate(instance, unmanaged.getDataProvider(), unmanaged.getGenerics());
	}
	
	public abstract void allocate(T instance, DataProvider provider, GenericContext genericContext) throws IOException;
	public abstract void setReference(T instance, Reference newRef);
	public abstract Reference getReference(T instance);
	public abstract ObjectPipe<Type, ?> getReferencedPipe(T instance);
}
