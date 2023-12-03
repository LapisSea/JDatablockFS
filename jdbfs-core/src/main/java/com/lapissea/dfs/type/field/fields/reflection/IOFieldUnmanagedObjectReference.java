package com.lapissea.dfs.type.field.fields.reflection;

import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.chunk.Chunk;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.exceptions.FieldIsNull;
import com.lapissea.dfs.exceptions.MalformedStruct;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.io.content.ContentWriter;
import com.lapissea.dfs.io.instancepipe.FixedVaryingStructPipe;
import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.objects.Reference;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.IOType;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.WordSpace;
import com.lapissea.dfs.type.field.VaryingSize;
import com.lapissea.dfs.type.field.access.FieldAccessor;
import com.lapissea.dfs.type.field.fields.RefField;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.UtilL;

import java.io.IOException;

import static com.lapissea.dfs.config.GlobalConfig.DEBUG_VALIDATION;
import static com.lapissea.dfs.type.field.annotations.IONullability.Mode.DEFAULT_IF_NULL;

public final class IOFieldUnmanagedObjectReference<T extends IOInstance<T>, ValueType extends IOInstance.Unmanaged<ValueType>> extends RefField.InstRef<T, ValueType>{
	
	
	private final Struct.Unmanaged<ValueType> struct;
	private final StructPipe<ValueType>       instancePipe;
	private final StructPipe<Reference>       referencePipe;
	
	public IOFieldUnmanagedObjectReference(FieldAccessor<T> accessor){
		this(accessor, null);
	}
	private IOFieldUnmanagedObjectReference(FieldAccessor<T> accessor, VaryingSize.Provider varProvider){
		super(accessor);
		if(getNullability() == DEFAULT_IF_NULL){
			throw new MalformedStruct(DEFAULT_IF_NULL + " is not supported for unmanaged objects");
		}
		
		if(varProvider != null){
			var pip = FixedVaryingStructPipe.tryVarying(Reference.STRUCT, varProvider);
			referencePipe = pip;
			initSizeDescriptor(pip.getFixedDescriptor());
		}else{
			referencePipe = Reference.standardPipe();
			initSizeDescriptor(referencePipe.getSizeDescriptor().map(this::getReference));
		}
		
		struct = Struct.Unmanaged.ofUnmanaged((Class<ValueType>)getType());
		instancePipe = StandardStructPipe.of(struct);
	}
	
	@Override
	public void allocate(T instance, DataProvider provider, GenericContext genericContext) throws IOException{
		AllocateTicket t;
		
		var desc  = instancePipe.getSizeDescriptor();
		var fixed = desc.getFixed(WordSpace.BYTE);
		if(fixed.isPresent()){
			t = AllocateTicket.bytes(fixed.getAsLong());
		}else{
			var min = desc.getMin(WordSpace.BYTE);
			var max = desc.getMax(WordSpace.BYTE).orElse(min + 8);
			t = AllocateTicket.bytes((min*2 + max)/3);
		}
		Chunk chunk = t.submit(provider);
		var   val   = makeValueObject(provider, chunk.getPtr().makeReference(), genericContext);
		set(null, instance, val);
	}
	
	@Override
	public void setReference(T instance, Reference newRef){
		var old = get(null, instance);
		if(old == null) throw new NotImplementedException();
		
		old.notifyReferenceMovement(newRef);
		assert old.getReference().equals(newRef);
	}
	
	@Override
	public ValueType get(VarPool<T> ioPool, T instance){
		var val = rawGet(ioPool, instance);
		if(val == null){
			if(nullable()) return null;
			throw new FieldIsNull(this);
		}
		return val;
	}
	@Override
	public boolean isNull(VarPool<T> ioPool, T instance){
		return rawGet(ioPool, instance) == null;
	}
	
	@Override
	public Reference getReference(T instance){
		return getReference(get(null, instance));
	}
	@Override
	public StructPipe<ValueType> getReferencedPipe(T instance){
		var val = get(null, instance);
		return val != null? val.getPipe() : null;
	}
	@Override
	public RefField<T, ValueType> maxAsFixedSize(VaryingSize.Provider varProvider){
		return new IOFieldUnmanagedObjectReference<>(getAccessor(), varProvider == null? VaryingSize.Provider.ALL_MAX : varProvider);
	}
	
	private Reference getReference(ValueType val){
		if(val == null){
			if(nullable()) return new Reference();
			throw new NullPointerException();
		}
		return val.getReference();
	}
	private ValueType makeValueObject(DataProvider provider, Reference readNew, GenericContext genericContext) throws IOException{
		if(DEBUG_VALIDATION && genericContext != null){
			var struct = declaringStruct();
			assert struct == null || UtilL.instanceOf(struct.getType(), genericContext.owner()) :
				genericContext.owner().getName() + " != " + struct.getType();
		}
		if(readNew.isNull()){
			if(nullable()) return null;
			throw new NullPointerException();
		}
		var type = IOType.of(getAccessor().getGenericType(genericContext));
		return struct.make(provider, readNew, type);
	}
	
	@Override
	public void write(VarPool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
		referencePipe.write(provider, dest, getReference(instance));
	}
	
	@Override
	public void read(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		var ref = referencePipe.readNew(provider, src, null);
		var val = makeValueObject(provider, ref, genericContext);
		set(ioPool, instance, val);
	}
	
	@Override
	public void skip(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		if(src.optionallySkipExact(referencePipe.getSizeDescriptor().getFixed(WordSpace.BYTE))){
			return;
		}
		
		referencePipe.skip(provider, src, makeContext(genericContext));
	}
}