package com.lapissea.cfs.type.field.fields.reflection;

import com.lapissea.cfs.chunk.AllocateTicket;
import com.lapissea.cfs.chunk.Chunk;
import com.lapissea.cfs.chunk.ChunkDataProvider;
import com.lapissea.cfs.exceptions.FieldIsNullException;
import com.lapissea.cfs.exceptions.MalformedStructLayout;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.instancepipe.ContiguousStructPipe;
import com.lapissea.cfs.io.instancepipe.FixedContiguousStructPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.TypeDefinition;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.access.IFieldAccessor;

import java.io.IOException;
import java.util.List;

import static com.lapissea.cfs.type.field.annotations.IONullability.Mode.*;

public class IOFieldUnmanagedObjectReference<T extends IOInstance<T>, ValueType extends IOInstance.Unmanaged<ValueType>> extends IOField.Ref<T, ValueType>{
	
	
	private final SizeDescriptor<T>           descriptor;
	private final Struct.Unmanaged<ValueType> struct;
	private final StructPipe<ValueType>       instancePipe;
	private final StructPipe<Reference>       referencePipe;
	
	public IOFieldUnmanagedObjectReference(IFieldAccessor<T> accessor){
		this(accessor, false);
	}
	public IOFieldUnmanagedObjectReference(IFieldAccessor<T> accessor, boolean fixed){
		super(accessor);
		if(getNullability()==DEFAULT_IF_NULL){
			throw new MalformedStructLayout(DEFAULT_IF_NULL+" is not supported for unmanaged objects");
		}
		
		if(fixed){
			referencePipe=FixedContiguousStructPipe.of(Reference.class);
			descriptor=new SizeDescriptor.Fixed<>(referencePipe.getSizeDescriptor().requireFixed());
		}else{
			referencePipe=ContiguousStructPipe.of(Reference.class);
			
			SizeDescriptor<Reference> s=referencePipe.getSizeDescriptor();
			descriptor=SizeDescriptor.overrideUnknown(s, instance->s.calcUnknown(getReference(instance)));
		}
		
		struct=(Struct.Unmanaged<ValueType>)Struct.Unmanaged.ofUnknown(getAccessor().getType());
		instancePipe=ContiguousStructPipe.of(struct);
	}
	
	@Override
	public void allocate(T instance, ChunkDataProvider provider, GenericContext genericContext) throws IOException{
		var            desc=instancePipe.getSizeDescriptor();
		AllocateTicket t;
		
		if(desc.hasFixed()) t=AllocateTicket.bytes(desc.getFixed().orElseThrow()).withDisabledResizing();
		else{
			var min=desc.getMin();
			var max=desc.getMax().orElse(min+8);
			t=AllocateTicket.bytes((min*2+max)/3);
		}
		Chunk chunk=t.submit(provider);
		var   val  =makeValueObject(provider, chunk.getPtr().makeReference(), genericContext);
		set(instance, val);
	}
	
	@Override
	public ValueType get(T instance){
		var val=super.get(instance);
		if(val==null){
			if(nullable()) return null;
			throw new FieldIsNullException(this);
		}
		return val;
	}
	@Override
	public Reference getReference(T instance){
		return getReference(get(instance));
	}
	@Override
	public StructPipe<ValueType> getReferencedPipe(T instance){
		var val=get(instance);
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
	private ValueType makeValueObject(ChunkDataProvider provider, Reference readNew, GenericContext genericContext) throws IOException{
		if(readNew.isNull()){
			if(nullable()) return null;
			throw new NullPointerException();
		}
		return struct.requireUnmanagedConstructor().create(provider, readNew, TypeDefinition.of(getAccessor().getGenericType(genericContext)));
	}
	
	@Override
	public SizeDescriptor<T> getSizeDescriptor(){
		return descriptor;
	}
	
	@Override
	public List<IOField<T, ?>> write(ChunkDataProvider provider, ContentWriter dest, T instance) throws IOException{
		referencePipe.write(provider, dest, getReference(instance));
		return List.of();
	}
	
	@Override
	public void read(ChunkDataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		set(instance, makeValueObject(provider, referencePipe.readNew(provider, src, null), genericContext));
	}
	
	@Override
	public void skipRead(ChunkDataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		var fixed=referencePipe.getSizeDescriptor().getFixed();
		if(fixed.isPresent()){
			src.skipExact(fixed.getAsLong());
			return;
		}
		
		referencePipe.readNew(provider, src, genericContext);
	}
}
