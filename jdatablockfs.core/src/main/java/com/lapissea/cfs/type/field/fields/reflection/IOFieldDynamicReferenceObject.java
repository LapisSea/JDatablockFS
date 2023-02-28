package com.lapissea.cfs.type.field.fields.reflection;

import com.lapissea.cfs.chunk.AllocateTicket;
import com.lapissea.cfs.chunk.Chunk;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.exceptions.MalformedStruct;
import com.lapissea.cfs.io.content.ContentOutputBuilder;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.instancepipe.ObjectPipe;
import com.lapissea.cfs.io.instancepipe.StandardStructPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.*;
import com.lapissea.cfs.type.field.*;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.cfs.type.field.fields.RefField;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.ShouldNeverHappenError;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static com.lapissea.cfs.type.field.annotations.IONullability.Mode.NULLABLE;

public class IOFieldDynamicReferenceObject<CTyp extends IOInstance<CTyp>, ValueType> extends RefField.ReferenceCompanion<CTyp, ValueType>{
	
	private static final StructPipe<Reference> STANDARD_REF = StandardStructPipe.of(Reference.class);
	
	private IOFieldPrimitive.FInt<CTyp> typeID;
	
	private final ObjectPipe<ValueType, ?> valuePipe = new ObjectPipe<>(){
		
		@Override
		public void write(DataProvider provider, ContentWriter dest, ValueType instance) throws IOException{
			throw NotImplementedException.infer();//TODO: implement .write()
		}
		@Override
		public void skip(DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
			throw NotImplementedException.infer();//TODO: implement .skip()
		}
		@Override
		public ValueType read(DataProvider provider, ContentReader src, ValueType instance, GenericContext genericContext) throws IOException{
			throw NotImplementedException.infer();//TODO: implement .read()
		}
		@Override
		public ValueType readNew(DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
			throw NotImplementedException.infer();//TODO: implement .readNew()
		}
		@Override
		public BasicSizeDescriptor<ValueType, Object> getSizeDescriptor(){
			return BasicSizeDescriptor.IFixed.Basic.of(0);
		}
		@Override
		public Object makeIOPool(){
			return null;
		}
	};
	
	public IOFieldDynamicReferenceObject(FieldAccessor<CTyp> accessor){
		super(accessor, SizeDescriptor.Fixed.empty());
		
		if(getNullability() == IONullability.Mode.DEFAULT_IF_NULL){
			throw new MalformedStruct("DEFAULT_IF_NULL is not supported on dynamic fields!");
		}
	}
	
	@Override
	public void init(){
		super.init();
		typeID = declaringStruct().getFields().requireExactInt(IOFieldTools.makeGenericIDFieldName(getAccessor()));
	}
	
	@Override
	public void set(VarPool<CTyp> ioPool, CTyp instance, ValueType value){
		super.set(ioPool, instance, switch(getNullability()){
			case DEFAULT_IF_NULL, NULLABLE -> value;
			case NOT_NULL -> Objects.requireNonNull(value);
		});
	}
	
	@Override
	public List<ValueGeneratorInfo<CTyp, ?>> getGenerators(){
		var idGenerator = new ValueGeneratorInfo<>(typeID, new ValueGenerator<CTyp, Integer>(){
			private IOTypeDB.TypeID getId(VarPool<CTyp> ioPool, DataProvider provider, CTyp instance, boolean record) throws IOException{
				var val = get(ioPool, instance);
				if(val == null) return new IOTypeDB.TypeID(-1, false);
				return provider.getTypeDb().toID(val, record);
			}
			@Override
			public boolean shouldGenerate(VarPool<CTyp> ioPool, DataProvider provider, CTyp instance) throws IOException{
				var id = getId(ioPool, provider, instance, false);
				if(!id.stored()) return true;
				var writtenId = typeID.getValue(ioPool, instance);
				return id.val() != writtenId;
			}
			@Override
			public Integer generate(VarPool<CTyp> ioPool, DataProvider provider, CTyp instance, boolean allowExternalMod) throws IOException{
				var typ = getId(ioPool, provider, instance, allowExternalMod);
				return typ.val();
			}
		});
		
		var gens = super.getGenerators();
		if(gens == null) return List.of(idGenerator);
		return Stream.concat(gens.stream(), Stream.of(idGenerator)).toList();
	}
	
	@Override
	protected ValueType newDefault(){
		throw new UnsupportedOperationException();
	}
	@Override
	protected Reference allocNew(DataProvider provider, ValueType val) throws IOException{
		var buf = new ContentOutputBuilder();
		DynamicSupport.writeValue(STANDARD_REF, provider, buf, val);
		Chunk chunk = AllocateTicket.bytes(buf.size()).withDataPopulated((p, io) -> buf.writeTo(io)).submit(provider);
		return chunk.getPtr().makeReference();
	}
	@Override
	public void allocate(CTyp instance, DataProvider provider, GenericContext genericContext) throws IOException{
	}
	@Override
	public void setReference(CTyp instance, Reference newRef){
		Objects.requireNonNull(newRef);
		if(newRef.isNull()){
			if(getNullability() == IONullability.Mode.NOT_NULL){
				throw new NullPointerException();
			}
		}
		setRef(instance, newRef);
	}
	@Override
	public Reference getReference(CTyp instance){
		return super.getReference(instance);
	}
	
	@Override
	public ObjectPipe<ValueType, ?> getReferencedPipe(CTyp instance){
		return valuePipe;
	}
	
	@Override
	public void write(VarPool<CTyp> ioPool, DataProvider provider, ContentWriter dest, CTyp instance) throws IOException{
		var val = get(ioPool, instance);
		var ref = getReference(instance);
		if(val != null && (ref == null || ref.isNull())){
			throw new ShouldNeverHappenError();//Generators have not been called if this is true
		}
		
		if(val != null){
			try(var io = ref.io(provider)){
				DynamicSupport.writeValue(STANDARD_REF, provider, io, val);
				io.trim();
			}
		}
	}
	
	private TypeLink getType(VarPool<CTyp> ioPool, DataProvider provider, CTyp instance) throws IOException{
		int id = typeID.getValue(ioPool, instance);
		return provider.getTypeDb().fromID(id);
	}
	
	private ValueType readValue(DataProvider provider, TypeLink type, Reference readNew, GenericContext genericContext) throws IOException{
		if(readNew.isNull()){
			if(getNullability() != NULLABLE){
				throw new NullPointerException();
			}
			return null;
		}
		try(var io = readNew.io(provider)){
			//noinspection unchecked
			return (ValueType)DynamicSupport.readTyp(STANDARD_REF, type, provider, io, genericContext);
		}
	}
	
	@Override
	public void read(VarPool<CTyp> ioPool, DataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException{
		var type = getType(ioPool, provider, instance);
		var ref  = Objects.requireNonNull(getRef(instance));
		set(ioPool, instance, readValue(provider, type, ref, genericContext));
	}
	
	@Override
	public void skip(VarPool<CTyp> ioPool, DataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException{
		//nothing to do. Reference field stores the actual pointer
	}
	
	@Override
	protected IOField<CTyp, ValueType> maxAsFixedSize(VaryingSize.Provider varProvider){
		return this;
	}
}
