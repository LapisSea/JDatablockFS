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
import com.lapissea.cfs.type.StagedInit;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.util.ShouldNeverHappenError;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

import static com.lapissea.cfs.GlobalConfig.DEBUG_VALIDATION;

public class IOFieldObjectReference<T extends IOInstance<T>, ValueType extends IOInstance<ValueType>> extends IOField.Ref<T, ValueType>{
	
	
	private final SizeDescriptor<T>     descriptor;
	private final Struct<ValueType>     struct;
	private final StructPipe<ValueType> instancePipe;
	
	private IOField<T, Reference> referenceField;
	
	@SuppressWarnings("unchecked")
	public IOFieldObjectReference(FieldAccessor<T> accessor){
		super(accessor);
		
		descriptor=SizeDescriptor.Fixed.of(0);
		
		struct=(Struct<ValueType>)Struct.ofUnknown(getAccessor().getType());
		var typ=accessor.getAnnotation(IOValue.Reference.class).map(IOValue.Reference::dataPipeType).orElseThrow();
		instancePipe=switch(typ){
			case FIXED -> {
				var pip=FixedContiguousStructPipe.of(struct);
				pip.waitForState(StagedInit.STATE_DONE);
				yield pip;
			}
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
		referenceField.set(null, instance, newRef);
	}
	
	private void allocAndSet(T instance, DataProvider provider, ValueType val) throws IOException{
		var ref=allocNew(provider, val);
		referenceField.set(null, instance, ref);
	}
	private Reference allocNew(DataProvider provider, ValueType val) throws IOException{
		Chunk chunk=AllocateTicket.withData(instancePipe, provider, val).submit(provider);
		return chunk.getPtr().makeReference();
	}
	
	@Override
	public ValueType get(Struct.Pool<T> ioPool, T instance){
		return getNullable(ioPool, instance, ()->null);
	}
	
	@Override
	public Reference getReference(T instance){
		return getRef(instance);
	}
	@Override
	public StructPipe<ValueType> getReferencedPipe(T instance){
		return instancePipe;
	}
	
	private Reference getRef(T instance){
		var ref=referenceField.get(null, instance);
		if(ref.isNull()){
			return switch(getNullability()){
				case NOT_NULL -> throw new NullPointerException();
				case NULLABLE -> get(null, instance)!=null?null:ref;
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
	public List<ValueGeneratorInfo<T, ?>> getGenerators(){
		return List.of(new ValueGeneratorInfo<>(referenceField, new ValueGenerator<>(){
			@Override
			public boolean shouldGenerate(Struct.Pool<T> ioPool, DataProvider provider, T instance){
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
			public Reference generate(Struct.Pool<T> ioPool, DataProvider provider, T instance, boolean allowExternalMod) throws IOException{
				var val=get(ioPool, instance);
				if(val==null&&getNullability()==IONullability.Mode.DEFAULT_IF_NULL){
					val=struct.requireEmptyConstructor().get();
				}
				
				if(val==null){
					return new Reference();
				}
				
				if(DEBUG_VALIDATION){
					var ref=getReference(instance);
					assert ref==null||ref.isNull();
				}
				if(!allowExternalMod) throw new RuntimeException("data modification should not be done here");
				return allocNew(provider, val);
			}
		}));
	}
	@Override
	public void write(Struct.Pool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
		var val=get(ioPool, instance);
		if(val==null&&getNullability()==IONullability.Mode.DEFAULT_IF_NULL){
			val=struct.requireEmptyConstructor().get();
		}
		var ref=getReference(instance);
		if(val!=null&&(ref==null||ref.isNull())){
			throw new ShouldNeverHappenError();//Generators have not been called if this is true
		}
		
		if(val!=null){
			try(var io=ref.io(provider)){
				instancePipe.write(provider, io, val);
			}
		}
	}
	
	@Override
	public void read(Struct.Pool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		set(ioPool, instance, readValue(provider, Objects.requireNonNull(getRef(instance)), genericContext));
	}
	
	@Override
	public void skipRead(Struct.Pool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		//nothing to do. Reference field stores the actual pointer
	}
}