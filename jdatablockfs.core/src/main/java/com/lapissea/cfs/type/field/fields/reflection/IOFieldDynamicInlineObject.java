package com.lapissea.cfs.type.field.fields.reflection;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.exceptions.MalformedStruct;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.instancepipe.StandardStructPipe;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.GetAnnotation;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.IOTypeDB;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.TypeLink;
import com.lapissea.cfs.type.VarPool;
import com.lapissea.cfs.type.WordSpace;
import com.lapissea.cfs.type.field.BehaviourSupport;
import com.lapissea.cfs.type.field.FieldSet;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.cfs.type.field.fields.NullFlagCompanyField;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Stream;

import static com.lapissea.cfs.type.StagedInit.STATE_DONE;

public final class IOFieldDynamicInlineObject<CTyp extends IOInstance<CTyp>, ValueType> extends NullFlagCompanyField<CTyp, ValueType>{
	
	@SuppressWarnings("unused")
	private static final class Usage implements FieldUsage{
		@Override
		public boolean isCompatible(Type type, GetAnnotation annotations){
			return IOFieldTools.isGeneric(annotations) && !annotations.isPresent(IOValue.Reference.class);
		}
		@Override
		public <T extends IOInstance<T>> IOField<T, ?> create(FieldAccessor<T> field){
			return new IOFieldDynamicInlineObject<>(field);
		}
		@Override
		@SuppressWarnings("rawtypes")
		public Set<Class<? extends IOField>> listFieldTypes(){
			return Set.of(IOFieldDynamicInlineObject.class);
		}
		@Override
		public <T extends IOInstance<T>> List<Behaviour<?, T>> annotationBehaviour(Class<IOField<T, ?>> fieldType){
			return List.of(
				Behaviour.of(IOValue.Generic.class, BehaviourSupport::genericID),
				Behaviour.of(IONullability.class, BehaviourSupport::ioNullability)
			);
		}
	}
	
	private IOFieldPrimitive.FInt<CTyp> typeID;
	
	public IOFieldDynamicInlineObject(FieldAccessor<CTyp> accessor){
		super(accessor);
		
		if(getNullability() == IONullability.Mode.DEFAULT_IF_NULL){
			throw new MalformedStruct("DEFAULT_IF_NULL is not supported on dynamic fields!");
		}
		
		Type type = accessor.getGenericType(null);
		
		long minKnownTypeSize = Long.MAX_VALUE;
		try{
			Struct<?>         struct  = Struct.ofUnknown(Utils.typeToRaw(type));
			SizeDescriptor<?> typDesc = StandardStructPipe.of(struct, STATE_DONE).getSizeDescriptor();
			minKnownTypeSize = typDesc.getMin(WordSpace.BYTE);
		}catch(IllegalArgumentException ignored){ }
		
		var refDesc = Reference.standardPipe().getSizeDescriptor();
		
		long minSize = Math.min(refDesc.getMin(WordSpace.BYTE), minKnownTypeSize);
		
		initSizeDescriptor(SizeDescriptor.Unknown.of(minSize, OptionalLong.empty(), (ioPool, prov, inst) -> {
			var val = get(null, inst);
			if(val == null) return 0;
			return DynamicSupport.calcSize(prov, val);
		}));
	}
	
	@Override
	public void init(FieldSet<CTyp> fields){
		super.init(fields);
		typeID = fields.requireExactInt(IOFieldTools.makeGenericIDFieldName(getAccessor()));
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
	public void write(VarPool<CTyp> ioPool, DataProvider provider, ContentWriter dest, CTyp instance) throws IOException{
		if(nullable() && getIsNull(ioPool, instance)) return;
		var val = get(ioPool, instance);
		DynamicSupport.writeValue(provider, dest, val);
	}
	
	private TypeLink getType(VarPool<CTyp> ioPool, DataProvider provider, CTyp instance) throws IOException{
		int id = typeID.getValue(ioPool, instance);
		return provider.getTypeDb().fromID(id);
	}
	
	@Override
	public void read(VarPool<CTyp> ioPool, DataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException{
		Object val;
		read:
		{
			if(nullable()){
				if(getIsNull(ioPool, instance)){
					val = null;
					break read;
				}
			}
			
			TypeLink typ = getType(ioPool, provider, instance);
			val = DynamicSupport.readTyp(typ, provider, src, genericContext);
		}
		//noinspection unchecked
		set(ioPool, instance, (ValueType)val);
	}
	
	@Override
	public void skip(VarPool<CTyp> ioPool, DataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException{
		if(nullable()){
			if(getIsNull(ioPool, instance)){
				return;
			}
		}
		
		TypeLink typ = getType(ioPool, provider, instance);
		DynamicSupport.skipTyp(typ, provider, src, genericContext);
	}
}
