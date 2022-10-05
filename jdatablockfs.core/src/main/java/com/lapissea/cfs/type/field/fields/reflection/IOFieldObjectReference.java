package com.lapissea.cfs.type.field.fields.reflection;

import com.lapissea.cfs.chunk.AllocateTicket;
import com.lapissea.cfs.chunk.Chunk;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.instancepipe.ContiguousStructPipe;
import com.lapissea.cfs.io.instancepipe.FixedContiguousStructPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.VarPool;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.cfs.type.field.fields.RefField;
import com.lapissea.util.ShouldNeverHappenError;

import java.io.IOException;
import java.util.Objects;

import static com.lapissea.cfs.type.StagedInit.STATE_DONE;

public class IOFieldObjectReference<T extends IOInstance<T>, ValueType extends IOInstance<ValueType>> extends RefField.ReferenceCompanion<T, ValueType> implements RefField.Inst<T, ValueType>{
	
	
	private final SizeDescriptor<T>     descriptor;
	private final Struct<ValueType>     struct;
	private final StructPipe<ValueType> instancePipe;
	
	@SuppressWarnings("unchecked")
	public IOFieldObjectReference(FieldAccessor<T> accessor){
		super(accessor);
		
		descriptor=SizeDescriptor.Fixed.empty();
		
		struct=(Struct<ValueType>)Struct.ofUnknown(getAccessor().getType());
		var typ=accessor.getAnnotation(IOValue.Reference.class).map(IOValue.Reference::dataPipeType).orElseThrow();
		instancePipe=switch(typ){
			case FIXED -> FixedContiguousStructPipe.of(struct, STATE_DONE);
			case FLEXIBLE -> ContiguousStructPipe.of(struct);
		};
		
	}
	
	@Override
	public void allocate(T instance, DataProvider provider, GenericContext genericContext) throws IOException{
		var val=newDefault();
		allocAndSet(instance, provider, val);
		set(null, instance, val);
	}
	@Override
	public void setReference(T instance, Reference newRef){
		Objects.requireNonNull(newRef);
		if(newRef.isNull()){
			if(getNullability()==IONullability.Mode.NOT_NULL){
				throw new NullPointerException();
			}
		}
		setRef(instance, newRef);
	}
	
	private void allocAndSet(T instance, DataProvider provider, ValueType val) throws IOException{
		var ref=allocNew(provider, val);
		setRef(instance, ref);
	}
	
	@Override
	protected Reference allocNew(DataProvider provider, ValueType val) throws IOException{
		Chunk chunk=AllocateTicket.withData(instancePipe, provider, val).submit(provider);
		return chunk.getPtr().makeReference();
	}
	
	@Override
	protected ValueType newDefault(){
		return struct.make();
	}
	
	@Override
	public ValueType get(VarPool<T> ioPool, T instance){
		return getNullable(ioPool, instance, ()->null);
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
		return readNew.readNew(provider, instancePipe, genericContext);
	}
	
	@Override
	public SizeDescriptor<T> getSizeDescriptor(){
		return descriptor;
	}
	
	@Override
	public void write(VarPool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
		var val=get(ioPool, instance);
		if(val==null&&getNullability()==IONullability.Mode.DEFAULT_IF_NULL){
			val=struct.make();
		}
		var ref=getReference(instance);
		if(val!=null&&(ref==null||ref.isNull())){
			throw new ShouldNeverHappenError();//Generators have not been called if this is true
		}
		
		if(val!=null){
			ref.write(provider, false, instancePipe, val);
		}
	}
	
	@Override
	public void read(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		set(ioPool, instance, readValue(provider, Objects.requireNonNull(getRef(instance)), genericContext));
	}
	
	@Override
	public void skip(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		//nothing to do. Reference field stores the actual pointer
	}
}
