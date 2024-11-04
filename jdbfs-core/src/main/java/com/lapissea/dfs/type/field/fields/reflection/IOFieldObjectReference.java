package com.lapissea.dfs.type.field.fields.reflection;

import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.core.chunk.Chunk;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.io.content.ContentWriter;
import com.lapissea.dfs.io.instancepipe.FixedStructPipe;
import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.objects.Reference;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.field.SizeDescriptor;
import com.lapissea.dfs.type.field.access.FieldAccessor;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.type.field.fields.RefField;
import com.lapissea.util.ShouldNeverHappenError;

import java.io.IOException;
import java.util.Objects;
import java.util.Set;

import static com.lapissea.dfs.io.instancepipe.StructPipe.STATE_IO_FIELD;

public final class IOFieldObjectReference<T extends IOInstance<T>, ValueType extends IOInstance<ValueType>> extends RefField.ReferenceCompanion<T, ValueType> implements RefField.Inst<T, ValueType>{
	
	
	private final Struct<ValueType>     struct;
	private final StructPipe<ValueType> instancePipe;
	
	@SuppressWarnings("unchecked")
	public IOFieldObjectReference(FieldAccessor<T> accessor){
		super(accessor, SizeDescriptor.Fixed.empty());
		
		struct = (Struct<ValueType>)Struct.ofUnknown(getType());
		var typ = accessor.getAnnotation(IOValue.Reference.class).map(IOValue.Reference::dataPipeType).orElseThrow();
		instancePipe = switch(typ){
			case FIXED -> FixedStructPipe.of(struct, STATE_IO_FIELD);
			case FLEXIBLE -> StandardStructPipe.of(struct);
		};
		
	}
	@Override
	protected Set<TypeFlag> computeTypeFlags(){
		return Set.of(TypeFlag.IO_INSTANCE);
	}
	
	@Override
	public void allocate(T instance, DataProvider provider, GenericContext genericContext) throws IOException{
		var val = newDefault();
		allocAndSet(instance, provider, val);
		set(null, instance, val);
	}
	@Override
	public void setReference(T instance, Reference newRef){
		Objects.requireNonNull(newRef);
		if(newRef.isNull()){
			if(isNonNullable()){
				throw new NullPointerException();
			}
		}
		setRef(instance, newRef);
	}
	
	private void allocAndSet(T instance, DataProvider provider, ValueType val) throws IOException{
		var ref = allocNew(provider, val);
		setRef(instance, ref);
	}
	
	@Override
	protected Reference allocNew(DataProvider provider, ValueType val) throws IOException{
		Chunk chunk = AllocateTicket.withData(instancePipe, provider, val).submit(provider);
		return chunk.getPtr().makeReference();
	}
	
	@Override
	protected ValueType newDefault(){
		return struct.make();
	}
	
	@Override
	public ValueType get(VarPool<T> ioPool, T instance){
		return getNullable(ioPool, instance, (ValueType)null);
	}
	@Override
	public boolean isNull(VarPool<T> ioPool, T instance){
		return isNullRawNullable(ioPool, instance);
	}
	
	@Override
	public StructPipe<ValueType> getReferencedPipe(T instance){
		return instancePipe;
	}
	
	private ValueType readValue(DataProvider provider, Reference readNew, GenericContext genericContext) throws IOException{
		if(readNew.isNull()){
			return switch(getNullability()){
				case NULLABLE -> null;
				case NOT_NULL -> throw new NullPointerException();
				case DEFAULT_IF_NULL -> struct.make();
			};
		}
		return readNew.readNew(provider, instancePipe, makeContext(genericContext));
	}
	
	@Override
	public void write(VarPool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
		var val = get(ioPool, instance);
		if(val == null && getNullability() == IONullability.Mode.DEFAULT_IF_NULL){
			val = struct.make();
		}
		var ref = getReference(instance);
		if(val != null && (ref == null || ref.isNull())){
			throw new ShouldNeverHappenError();//Generators have not been called if this is true
		}
		
		if(val != null){
			ref.write(provider, false, instancePipe, val);
		}
	}
	
	@Override
	public void read(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		set(ioPool, instance, readValue(provider, Objects.requireNonNull(getRef(instance)), genericContext));
	}
	
	@Override
	public void skip(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext){
		//nothing to do. Reference field stores the actual pointer
	}
}
