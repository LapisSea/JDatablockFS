package com.lapissea.cfs.type.field.fields.reflection;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.instancepipe.StandardStructPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.GetAnnotation;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.VarPool;
import com.lapissea.cfs.type.WordSpace;
import com.lapissea.cfs.type.field.FieldSet;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.cfs.type.field.fields.NullFlagCompanyField;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class IOFieldInlineSealedObject<CTyp extends IOInstance<CTyp>, ValueType extends IOInstance<ValueType>> extends NullFlagCompanyField<CTyp, ValueType>{
	
	@SuppressWarnings({"unused", "rawtypes"})
	private static final class Usage implements FieldUsage{
		
		@Override
		public boolean isCompatible(Type type, GetAnnotation annotations){
			return IOFieldInlineSealedObject.isCompatible(type);
		}
		
		@Override
		public <T extends IOInstance<T>> IOField<T, IOInstance> create(FieldAccessor<T> field, GenericContext genericContext){
			return new IOFieldInlineSealedObject<>(field);
		}
	}
	
	public static boolean isCompatible(Type type){
		var universe = Utils.getSealedUniverse(Utils.typeToRaw(type), false);
		return universe.filter(u -> u.stream().allMatch(IOInstance::isInstance)).isPresent();
	}
	
	private final Map<Class<ValueType>, StructPipe<ValueType>> typeToPipe;
	private final Class<ValueType>                             rootType;
	private       IOFieldPrimitive.FInt<CTyp>                  universeID;
	
	private IOFieldInlineSealedObject(FieldAccessor<CTyp> accessor){
		super(accessor);
		//noinspection unchecked
		rootType = (Class<ValueType>)accessor.getType();
		var universe = Utils.getSealedUniverse(rootType, false).orElseThrow();
		typeToPipe = universe.stream().collect(Collectors.toUnmodifiableMap(t -> t, StandardStructPipe::of));
		
		var sizes = typeToPipe.values().stream().map(StructPipe::getSizeDescriptor).toList();
		
		var wordSpace = sizes.stream().map(SizeDescriptor::getWordSpace).reduce(WordSpace::min).orElseThrow();
		var minSize   = nullable()? 0 : sizes.stream().mapToLong(s -> s.getMin(wordSpace)).min().orElseThrow();
		var maxSize   = sizes.stream().map(s -> s.getMax(wordSpace)).reduce((a, b) -> Utils.combineIfBoth(a, b, Math::max)).orElseThrow();
		
		initSizeDescriptor(SizeDescriptor.Unknown.of(
			wordSpace, minSize, maxSize,
			(ioPool, prov, inst) -> {
				var val = get(null, inst);
				if(val == null){
					if(!nullable()) throw new NullPointerException();
					return 0;
				}
				var instancePipe = typeToPipe.get(val.getClass());
				
				return instancePipe.calcUnknownSize(prov, val, wordSpace);
			}
		));
	}
	
	@Override
	public void init(FieldSet<CTyp> ioFields){
		super.init(ioFields);
		universeID = ioFields.requireExactInt(IOFieldTools.makeUniverseIDFieldName(getAccessor()));
	}
	
	private int getUniverseID(VarPool<CTyp> ioPool, CTyp instance){
		return universeID.getValue(ioPool, instance);
	}
	
	@Override
	@SuppressWarnings("unchecked")
	public List<ValueGeneratorInfo<CTyp, ?>> getGenerators(){
		var gen = new ValueGeneratorInfo<>(universeID, new ValueGenerator<>(){
			@Override
			public boolean shouldGenerate(VarPool<CTyp> ioPool, DataProvider provider, CTyp instance) throws IOException{
				var id     = getUniverseID(ioPool, instance);
				var actual = valId(ioPool, provider, instance, false);
				return id != actual;
			}
			@Override
			public Integer generate(VarPool<CTyp> ioPool, DataProvider provider, CTyp instance, boolean allowExternalMod) throws IOException{
				return valId(ioPool, provider, instance, allowExternalMod);
			}
			private int valId(VarPool<CTyp> ioPool, DataProvider provider, CTyp instance, boolean allowExternalMod) throws IOException{
				var val = get(ioPool, instance);
				if(val == null) return 0;
				var db   = provider.getTypeDb();
				var type = (Class<ValueType>)val.getClass();
				return db.toID(rootType, type, allowExternalMod);
			}
		});
		
		var sup = super.getGenerators();
		if(sup == null) return List.of(gen);
		return Stream.concat(sup.stream(), Stream.of(gen)).toList();
	}
	
	@Override
	public ValueType get(VarPool<CTyp> ioPool, CTyp instance){
		return getNullable(ioPool, instance);
	}
	@Override
	public boolean isNull(VarPool<CTyp> ioPool, CTyp instance){
		return isNullRawNullable(ioPool, instance);
	}
	
	@Override
	public void set(VarPool<CTyp> ioPool, CTyp instance, ValueType value){
		super.set(ioPool, instance, switch(getNullability()){
			case DEFAULT_IF_NULL, NULLABLE -> value;
			case NOT_NULL -> Objects.requireNonNull(value);
		});
	}
	
	@Override
	public void write(VarPool<CTyp> ioPool, DataProvider provider, ContentWriter dest, CTyp instance) throws IOException{
		var val = get(ioPool, instance);
		if(nullable()){
			if(val == null){
				return;
			}
		}
		var instancePipe = typeToPipe.get(val.getClass());
		
		instancePipe.write(provider, dest, val);
	}
	
	private ValueType readNew(VarPool<CTyp> ioPool, DataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException{
		if(nullable()){
			boolean isNull = getIsNull(ioPool, instance);
			if(isNull){
				return null;
			}
		}
		
		var id           = getUniverseID(ioPool, instance);
		var type         = provider.getTypeDb().fromID(rootType, id);
		var instancePipe = typeToPipe.get(type);
		
		return instancePipe.readNew(provider, src, genericContext);
	}
	
	@Override
	public void read(VarPool<CTyp> ioPool, DataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException{
		set(ioPool, instance, readNew(ioPool, provider, src, instance, genericContext));
	}
	
	@Override
	public void skip(VarPool<CTyp> ioPool, DataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException{
		if(src.optionallySkipExact(getSizeDescriptor().getFixed(WordSpace.BYTE))){
			return;
		}
		
		if(nullable()){
			boolean isNull = getIsNull(ioPool, instance);
			if(isNull){
				return;
			}
		}
		
		var id   = getUniverseID(ioPool, instance);
		var type = provider.getTypeDb().fromID(rootType, id);
		
		var instancePipe = typeToPipe.get(type);
		
		instancePipe.skip(provider, src, genericContext);
	}
}
