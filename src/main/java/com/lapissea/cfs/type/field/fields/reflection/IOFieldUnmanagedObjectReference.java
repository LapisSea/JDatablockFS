package com.lapissea.cfs.type.field.fields.reflection;

import com.lapissea.cfs.chunk.AllocateTicket;
import com.lapissea.cfs.chunk.Chunk;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.exceptions.FieldIsNullException;
import com.lapissea.cfs.exceptions.MalformedStructLayout;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.instancepipe.ContiguousStructPipe;
import com.lapissea.cfs.io.instancepipe.FixedContiguousStructPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.*;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.util.NotImplementedException;

import java.io.IOException;

import static com.lapissea.cfs.type.field.annotations.IONullability.Mode.DEFAULT_IF_NULL;

public class IOFieldUnmanagedObjectReference<T extends IOInstance<T>, ValueType extends IOInstance.Unmanaged<ValueType>> extends IOField.Ref<T, ValueType>{
	
	
	private final SizeDescriptor<T>           descriptor;
	private final Struct.Unmanaged<ValueType> struct;
	private final StructPipe<ValueType>       instancePipe;
	private final StructPipe<Reference>       referencePipe;
	
	public IOFieldUnmanagedObjectReference(FieldAccessor<T> accessor){
		this(accessor, false);
	}
	public IOFieldUnmanagedObjectReference(FieldAccessor<T> accessor, boolean fixed){
		super(accessor);
		if(getNullability()==DEFAULT_IF_NULL){
			throw new MalformedStructLayout(DEFAULT_IF_NULL+" is not supported for unmanaged objects");
		}
		
		if(fixed){
			var pip=FixedContiguousStructPipe.of(Reference.class);
			referencePipe=pip;
			descriptor=pip.getFixedDescriptor();
		}else{
			referencePipe=ContiguousStructPipe.of(Reference.class);
			descriptor=referencePipe.getSizeDescriptor().map(this::getReference);
		}
		
		struct=Struct.Unmanaged.ofUnmanaged((Class<ValueType>)getAccessor().getType());
		instancePipe=ContiguousStructPipe.of(struct);
	}
	
	@Override
	public void allocate(T instance, DataProvider provider, GenericContext genericContext) throws IOException{
		AllocateTicket t;
		
		var desc =instancePipe.getSizeDescriptor();
		var fixed=desc.getFixed(WordSpace.BYTE);
		if(fixed.isPresent()){
			t=AllocateTicket.bytes(fixed.getAsLong()).withDisabledResizing();
		}else{
			var min=desc.getMin(WordSpace.BYTE);
			var max=desc.getMax(WordSpace.BYTE).orElse(min+8);
			t=AllocateTicket.bytes((min*2+max)/3);
		}
		Chunk chunk=t.submit(provider);
		var   val  =makeValueObject(provider, chunk.getPtr().makeReference(), genericContext);
		set(null, instance, val);
	}
	
	@Override
	public void setReference(T instance, Reference newRef){
		var old=get(null, instance);
		if(old==null) throw new NotImplementedException();
		
		old.notifyReferenceMovement(newRef);
		assert old.getReference().equals(newRef);
	}
	
	@Override
	public ValueType get(Struct.Pool<T> ioPool, T instance){
		var val=super.get(ioPool, instance);
		if(val==null){
			if(nullable()) return null;
			throw new FieldIsNullException(this);
		}
		return val;
	}
	@Override
	public Reference getReference(T instance){
		return getReference(get(null, instance));
	}
	@Override
	public StructPipe<ValueType> getReferencedPipe(T instance){
		var val=get(null, instance);
		return val!=null?val.getPipe():null;
	}
	@Override
	public Ref<T, ValueType> implMaxAsFixedSize(){
		return new IOFieldUnmanagedObjectReference<>(getAccessor(), true);
	}
	
	private Reference getReference(ValueType val){
		if(val==null){
			if(nullable()) return new Reference();
			throw new NullPointerException();
		}
		return val.getReference();
	}
	private ValueType makeValueObject(DataProvider provider, Reference readNew, GenericContext genericContext) throws IOException{
		if(readNew.isNull()){
			if(nullable()) return null;
			throw new NullPointerException();
		}
		return struct.requireUnmanagedConstructor().create(provider, readNew, TypeLink.of(getAccessor().getGenericType(genericContext)));
	}
	
	@Override
	public SizeDescriptor<T> getSizeDescriptor(){
		return descriptor;
	}
	
	@Override
	public void write(Struct.Pool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
		referencePipe.write(provider, dest, getReference(instance));
	}
	
	@Override
	public void read(Struct.Pool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		set(ioPool, instance, makeValueObject(provider, referencePipe.readNew(provider, src, null), genericContext));
	}
	
	@Override
	public void skipRead(Struct.Pool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		var fixed=referencePipe.getSizeDescriptor().getFixed(WordSpace.BYTE);
		if(fixed.isPresent()){
			src.skipExact(fixed.getAsLong());
			return;
		}
		
		referencePipe.readNew(provider, src, genericContext);
	}
}
