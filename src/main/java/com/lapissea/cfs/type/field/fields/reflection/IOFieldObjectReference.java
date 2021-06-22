package com.lapissea.cfs.type.field.fields.reflection;

import com.lapissea.cfs.chunk.AllocateTicket;
import com.lapissea.cfs.chunk.Chunk;
import com.lapissea.cfs.chunk.ChunkDataProvider;
import com.lapissea.cfs.exceptions.MalformedStructLayout;
import com.lapissea.cfs.exceptions.UnknownSizePredictionException;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.instancepipe.ContiguousStructPipe;
import com.lapissea.cfs.io.instancepipe.FixedContiguousStructPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.access.IFieldAccessor;

import java.io.IOException;

import static com.lapissea.cfs.type.field.annotations.IONullability.Mode.*;

public class IOFieldObjectReference<T extends IOInstance<T>, ValueType extends IOInstance<ValueType>> extends IOField.Ref<T, ValueType>{
	
	
	private final SizeDescriptor<T>     descriptor;
	private final Struct<ValueType>     struct;
	private final StructPipe<ValueType> instancePipe;
	private final StructPipe<Reference> referencePipe;
	
	private IOField<T, Reference> referenceField;
	
	public IOFieldObjectReference(IFieldAccessor<T> accessor){
		this(accessor, false);
	}
	public IOFieldObjectReference(IFieldAccessor<T> accessor, boolean fixed){
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
			descriptor=SizeDescriptor.overrideUnknown(s, instance->{
				var ref=getReference(instance);
				if(ref==null) throw new UnknownSizePredictionException();
				return s.calcUnknown(ref);
			});
		}
		
		struct=(Struct<ValueType>)Struct.ofUnknown(getAccessor().getType());
		instancePipe=ContiguousStructPipe.of(struct);
		
		
	}
	@Override
	public void init(){
		super.init();
		referenceField=getDependencies().exact(Reference.class, IOFieldTools.makeRefName(getAccessor())).orElseThrow();
	}
	
	@Override
	public void allocate(T instance, ChunkDataProvider provider) throws IOException{
		ValueType val=struct.requireEmptyConstructor().get();
		alloc(instance, provider, val);
		set(instance, val);
	}
	private void alloc(T instance, ChunkDataProvider provider, ValueType val) throws IOException{
		Chunk chunk=AllocateTicket.withData(instancePipe, val).submit(provider);
		referenceField.set(instance, chunk.getPtr().makeReference());
	}
	
	@Override
	public ValueType get(T instance){
		var val=super.get(instance);
		if(val==null){
			if(nullable()) return null;
			throw new NullPointerException();
		}
		return val;
	}
	@Override
	public Reference getReference(T instance){
		return getRef(instance);
	}
	@Override
	public Ref<T, ValueType> implMaxAsFixedSize(){
		return new IOFieldObjectReference<>(getAccessor(), true);
	}
	
	private Reference getRef(T instance){
		var ref=referenceField.get(instance);
		if(ref.isNull()&&!nullable()){
			if(get(instance)!=null) return null;
			throw new NullPointerException();
		}
		return ref;
	}
	private ValueType readValue(ChunkDataProvider provider, Reference readNew) throws IOException{
		if(readNew.isNull()){
			if(nullable()) return null;
			throw new NullPointerException();
		}
		ValueType val=struct.requireEmptyConstructor().get();
		try(var io=readNew.io(provider)){
			instancePipe.read(provider, io, val);
		}
		return val;
	}
	
	@Override
	public SizeDescriptor<T> getSizeDescriptor(){
		return descriptor;
	}
	
	@Override
	public void write(ChunkDataProvider provider, ContentWriter dest, T instance) throws IOException{
		var val=get(instance);
		var ref=getReference(instance);
		if(val!=null&&(ref==null||ref.isNull())){
			alloc(instance, provider, val);
			ref=getReference(instance);
		}
		
		try(var io=ref.io(provider)){
			instancePipe.write(provider, io, val);
		}
		
		referencePipe.write(provider, dest, ref);
	}
	
	@Override
	public void read(ChunkDataProvider provider, ContentReader src, T instance) throws IOException{
		set(instance, readValue(provider, referencePipe.readNew(provider, src)));
	}
}
