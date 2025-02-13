package com.lapissea.dfs.type.field.fields.reflection;

import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.core.chunk.Chunk;
import com.lapissea.dfs.exceptions.MalformedStruct;
import com.lapissea.dfs.io.content.ContentOutputBuilder;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.io.content.ContentWriter;
import com.lapissea.dfs.io.instancepipe.ObjectPipe;
import com.lapissea.dfs.objects.Reference;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.GetAnnotation;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.IOType;
import com.lapissea.dfs.type.IOTypeDB;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.field.BasicSizeDescriptor;
import com.lapissea.dfs.type.field.BehaviourSupport;
import com.lapissea.dfs.type.field.FieldNames;
import com.lapissea.dfs.type.field.FieldSet;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.IOFieldTools;
import com.lapissea.dfs.type.field.SizeDescriptor;
import com.lapissea.dfs.type.field.VaryingSize;
import com.lapissea.dfs.type.field.access.FieldAccessor;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.type.field.fields.RefField;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.ShouldNeverHappenError;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class IOFieldDynamicReferenceObject<CTyp extends IOInstance<CTyp>, ValueType> extends RefField.ReferenceCompanion<CTyp, ValueType>{
	
	@SuppressWarnings("unused")
	private static final class Usage implements FieldUsage{
		@Override
		public boolean isCompatible(Type type, GetAnnotation annotations){
			return IOFieldTools.isGeneric(annotations) && annotations.isPresent(IOValue.Reference.class);
		}
		@Override
		public <T extends IOInstance<T>> IOField<T, ?> create(FieldAccessor<T> field){
			return new IOFieldDynamicReferenceObject<>(field);
		}
		@Override
		@SuppressWarnings("rawtypes")
		public Set<Class<? extends IOField>> listFieldTypes(){ return Set.of(IOFieldDynamicReferenceObject.class); }
		@Override
		public <T extends IOInstance<T>> List<Behaviour<?, T>> annotationBehaviour(Class<IOField<T, ?>> fieldType){
			return List.of(
				Behaviour.noop(IONullability.class),
				Behaviour.of(IOValue.Generic.class, BehaviourSupport::genericID),
				Behaviour.of(IOValue.Reference.class, BehaviourSupport::referenceCompanion)
			);
		}
	}
	
	private IOFieldPrimitive.FInt<CTyp> typeID;
	
	private final ObjectPipe.NoPool<ValueType> valuePipe = new ObjectPipe.NoPool<>(){
		
		@Override
		public void write(DataProvider provider, ContentWriter dest, ValueType instance){
			throw NotImplementedException.infer();//TODO: implement .write()
		}
		@Override
		public void skip(DataProvider provider, ContentReader src, GenericContext genericContext){
			throw NotImplementedException.infer();//TODO: implement .skip()
		}
		@Override
		public ValueType readNew(DataProvider provider, ContentReader src, GenericContext genericContext){
			throw NotImplementedException.infer();//TODO: implement .readNew()
		}
		@Override
		public BasicSizeDescriptor<ValueType, Void> getSizeDescriptor(){
			return BasicSizeDescriptor.Unknown.of((pool, prov, value) -> DynamicSupport.calcSize(prov, value));
		}
	};
	
	public IOFieldDynamicReferenceObject(FieldAccessor<CTyp> accessor){
		super(accessor, SizeDescriptor.Fixed.empty());
		
		if(getNullability() == IONullability.Mode.DEFAULT_IF_NULL){
			throw new MalformedStruct("DEFAULT_IF_NULL is not supported on dynamic fields!");
		}
	}
	@Override
	protected Set<TypeFlag> computeTypeFlags(){
		return Set.of(TypeFlag.DYNAMIC);
	}
	
	@Override
	public void init(FieldSet<CTyp> fields){
		super.init(fields);
		typeID = fields.requireExactInt(FieldNames.genericID(getAccessor()));
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
				return provider.getTypeDb().objToID(val, record);
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
		
		return Iters.concatN1(super.getGenerators(), idGenerator).toModList();
	}
	
	@Override
	protected ValueType newDefault(){
		throw new UnsupportedOperationException();
	}
	@Override
	protected Reference allocNew(DataProvider provider, ValueType val) throws IOException{
		var buf = new ContentOutputBuilder();
		DynamicSupport.writeValue(provider, buf, val);
		Chunk chunk = AllocateTicket.bytes(buf.size()).withDataPopulated((p, io) -> buf.writeTo(io)).submit(provider);
		return chunk.getPtr().makeReference();
	}
	@Override
	public void allocate(CTyp instance, DataProvider provider, GenericContext genericContext){
	}
	@Override
	public void setReference(CTyp instance, Reference newRef){
		Objects.requireNonNull(newRef);
		if(newRef.isNull()){
			if(isNonNullable()){
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
				DynamicSupport.writeValue(provider, io, val);
				io.trim();
			}
		}
	}
	
	private IOType getType(VarPool<CTyp> ioPool, DataProvider provider, CTyp instance) throws IOException{
		int id = typeID.getValue(ioPool, instance);
		return provider.getTypeDb().fromID(id);
	}
	
	private ValueType readValue(DataProvider provider, IOType type, Reference readNew, GenericContext genericContext) throws IOException{
		try(var io = readNew.io(provider)){
			//noinspection unchecked
			return (ValueType)DynamicSupport.readTyp(type, provider, io, makeContext(genericContext));
		}
	}
	
	@Override
	public void read(VarPool<CTyp> ioPool, DataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException{
		var       ref = Objects.requireNonNull(getRef(instance));
		ValueType val;
		if(ref.isNull()){
			if(!nullable()){
				throw new NullPointerException();
			}
			val = null;
		}else{
			var type = getType(ioPool, provider, instance);
			val = readValue(provider, type, ref, makeContext(genericContext));
		}
		set(ioPool, instance, val);
	}
	
	@Override
	public void skip(VarPool<CTyp> ioPool, DataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext){
		//nothing to do. Reference field stores the actual pointer
	}
	
	@Override
	protected IOField<CTyp, ValueType> maxAsFixedSize(VaryingSize.Provider varProvider){
		return this;
	}
}
