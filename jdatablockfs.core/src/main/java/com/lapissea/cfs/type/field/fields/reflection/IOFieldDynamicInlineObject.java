package com.lapissea.cfs.type.field.fields.reflection;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.exceptions.MalformedStruct;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.instancepipe.StandardStructPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.*;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.cfs.type.field.fields.NullFlagCompanyField;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.stream.Stream;

import static com.lapissea.cfs.type.StagedInit.STATE_DONE;

public class IOFieldDynamicInlineObject<CTyp extends IOInstance<CTyp>, ValueType> extends NullFlagCompanyField<CTyp, ValueType>{
	
	private static final StructPipe<Reference> REF_PIPE = StandardStructPipe.of(Reference.class);
	
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
		
		var refDesc = REF_PIPE.getSizeDescriptor();
		
		long minSize = Math.min(refDesc.getMin(WordSpace.BYTE), minKnownTypeSize);
		
		initSizeDescriptor(SizeDescriptor.Unknown.of(minSize, OptionalLong.empty(), (ioPool, prov, inst) -> {
			var val = get(null, inst);
			if(val == null) return 0;
			return DynamicSupport.calcSize(REF_PIPE, prov, val);
		}));
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
	public void write(VarPool<CTyp> ioPool, DataProvider provider, ContentWriter dest, CTyp instance) throws IOException{
		if(nullable() && getIsNull(ioPool, instance)) return;
		var val = get(ioPool, instance);
		DynamicSupport.writeValue(REF_PIPE, provider, dest, val);
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
			val = DynamicSupport.readTyp(REF_PIPE, typ, provider, src, genericContext);
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
		DynamicSupport.skipTyp(REF_PIPE, typ, provider, src, genericContext);
	}
}
