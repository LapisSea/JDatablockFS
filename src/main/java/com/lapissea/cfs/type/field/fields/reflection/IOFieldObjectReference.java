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
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.util.NotImplementedException;

import java.io.IOException;
import java.util.Objects;

import static com.lapissea.cfs.GlobalConfig.DEBUG_VALIDATION;

public class IOFieldObjectReference<T extends IOInstance<T>, ValueType extends IOInstance<ValueType>> extends IOField.Ref<T, ValueType>{
	
	
	private final SizeDescriptor<T>     descriptor;
	private final Struct<ValueType>     struct;
	private final StructPipe<ValueType> instancePipe;
	
	private IOField<T, Reference> referenceField;
	
	public IOFieldObjectReference(FieldAccessor<T> accessor){
		this(accessor, false);
	}
	@SuppressWarnings("unchecked")
	public IOFieldObjectReference(FieldAccessor<T> accessor, boolean fixed){
		super(accessor);
		
		descriptor=SizeDescriptor.Fixed.of(0);
		
		struct=(Struct<ValueType>)Struct.ofUnknown(getAccessor().getType());
		var typ=accessor.getAnnotation(IOValue.Reference.class).map(IOValue.Reference::dataPipeType).orElseThrow();
		instancePipe=switch(typ){
			case FIXED -> FixedContiguousStructPipe.of(struct);
			case FLEXIBLE -> ContiguousStructPipe.of(struct);
		};
		
	}
	@Override
	public void init(){
		super.init();
		referenceField=getDependencies().requireExact(Reference.class, IOFieldTools.makeRefName(getAccessor()));
	}
	
	@Override
	public void allocate(T instance, DataProvider provider, GenericContext genericContext) throws IOException{
		ValueType val=struct.requireEmptyConstructor().get();
		alloc(instance, provider, val);
		set(instance, val);
	}
	@Override
	public void setReference(T instance, Reference newRef){
		Objects.requireNonNull(newRef);
		if(newRef.isNull()){
			if(getNullability()==IONullability.Mode.NOT_NULL){
				throw new NullPointerException();
			}
		}
		referenceField.set(instance, newRef);
	}
	
	private void alloc(T instance, DataProvider provider, ValueType val) throws IOException{
		Chunk chunk=AllocateTicket.withData(instancePipe, val).submit(provider);
		referenceField.set(instance, chunk.getPtr().makeReference());
	}
	
	@Override
	public ValueType get(T instance){
		var val=super.get(instance);
		return switch(getNullability()){
			case NULLABLE, DEFAULT_IF_NULL -> val;
			case NOT_NULL -> requireValNN(val);
		};
	}
	@Override
	public Reference getReference(T instance){
		return getRef(instance);
	}
	@Override
	public StructPipe<ValueType> getReferencedPipe(T instance){
		return instancePipe;
	}
	@Override
	public Ref<T, ValueType> implMaxAsFixedSize(){
		return new IOFieldObjectReference<>(getAccessor(), true);
	}
	
	private Reference getRef(T instance){
		var ref=referenceField.get(instance);
		if(ref.isNull()){
			return switch(getNullability()){
				case NOT_NULL -> throw new NullPointerException();
				case NULLABLE -> get(instance)!=null?null:ref;
				case DEFAULT_IF_NULL -> null;
			};
			
		}
		return ref;
	}
	private ValueType readValue(DataProvider provider, Reference readNew, GenericContext genericContext) throws IOException{
		if(readNew.isNull()){
			return switch(getNullability()){
				case NULLABLE -> null;
				case NOT_NULL -> throw new NullPointerException();
				case DEFAULT_IF_NULL -> struct.requireEmptyConstructor().get();
			};
		}
		ValueType val=struct.requireEmptyConstructor().get();
		try(var io=readNew.io(provider)){
			instancePipe.read(provider, io, val, genericContext);
		}
		return val;
	}
	
	@Override
	public SizeDescriptor<T> getSizeDescriptor(){
		return descriptor;
	}
	
	@Override
	public void write(DataProvider provider, ContentWriter dest, T instance) throws IOException{
		var val=get(instance);
		if(val==null&&getNullability()==IONullability.Mode.DEFAULT_IF_NULL){
			val=struct.requireEmptyConstructor().get();
		}
		var ref=getReference(instance);
		if(val!=null&&(ref==null||ref.isNull())){
			alloc(instance, provider, val);
			if(DEBUG_VALIDATION){
				getReference(instance).requireNonNull();
			}
			throw new NotImplementedException("implement generation of reference field!");
//			return List.of(referenceField); TODO: implement this you lazy bastard
		}
		
		if(val!=null){
			try(var io=ref.io(provider)){
				instancePipe.write(provider, io, val);
			}
		}
	}
	
	@Override
	public void read(DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		set(instance, readValue(provider, Objects.requireNonNull(getRef(instance)), genericContext));
	}
	
	@Override
	public void skipRead(DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		throw NotImplementedException.infer();//TODO: implement IOFieldObjectReference.skipRead()
	}
}
