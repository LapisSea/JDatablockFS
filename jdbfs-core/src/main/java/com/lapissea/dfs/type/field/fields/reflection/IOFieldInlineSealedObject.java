package com.lapissea.dfs.type.field.fields.reflection;

import com.lapissea.dfs.SealedUtil;
import com.lapissea.dfs.Utils;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.io.content.ContentWriter;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.GetAnnotation;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.WordSpace;
import com.lapissea.dfs.type.field.Annotations;
import com.lapissea.dfs.type.field.BehaviourSupport;
import com.lapissea.dfs.type.field.FieldNames;
import com.lapissea.dfs.type.field.FieldSet;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.VirtualFieldDefinition;
import com.lapissea.dfs.type.field.access.FieldAccessor;
import com.lapissea.dfs.type.field.annotations.IODependency;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.type.field.fields.NullFlagCompanyField;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import static com.lapissea.dfs.type.field.StoragePool.IO;

public final class IOFieldInlineSealedObject<CTyp extends IOInstance<CTyp>, ValueType extends IOInstance<ValueType>> extends NullFlagCompanyField<CTyp, ValueType>{
	
	@SuppressWarnings({"unused", "rawtypes"})
	private static final class Usage implements FieldUsage{
		
		private static <T extends IOInstance<T>> BehaviourRes<T> idBehaviour(FieldAccessor<T> field){
			return new BehaviourRes<T>(new VirtualFieldDefinition<>(
				IO, FieldNames.universeID(field), int.class,
				List.of(
					Annotations.make(IODependency.VirtualNumSize.class),
					IOValue.Unsigned.INSTANCE
				)
			));
		}
		@Override
		public boolean isCompatible(Type type, GetAnnotation annotations){
			return IOFieldInlineSealedObject.isCompatible(type);
		}
		
		@Override
		public <T extends IOInstance<T>> IOField<T, IOInstance> create(FieldAccessor<T> field){
			return new IOFieldInlineSealedObject<>(field);
		}
		@Override
		@SuppressWarnings("rawtypes")
		public Set<Class<? extends IOField>> listFieldTypes(){
			return Set.of(IOFieldInlineSealedObject.class);
		}
		@Override
		public <T extends IOInstance<T>> List<Behaviour<?, T>> annotationBehaviour(Class<IOField<T, ?>> fieldType){
			return List.of(
				Behaviour.of(IOValue.class, Usage::idBehaviour),
				Behaviour.of(IONullability.class, BehaviourSupport::ioNullability)
			);
		}
	}
	
	public static boolean isCompatible(Type type){
		return SealedUtil.getSealedUniverse(Utils.typeToRaw(type), false).filter(IOInstance::isInstance).isPresent();
	}
	
	private final Map<Class<ValueType>, StructPipe<ValueType>> typeToPipe;
	private final Class<ValueType>                             rootType;
	private       IOFieldPrimitive.FInt<CTyp>                  universeID;
	
	private IOFieldInlineSealedObject(FieldAccessor<CTyp> accessor){
		super(accessor);
		//noinspection unchecked
		rootType = (Class<ValueType>)accessor.getType();
		var universe = SealedUtil.getSealedUniverse(rootType, false)
		                         .flatMap(SealedUtil.SealedInstanceUniverse::of).orElseThrow();
		typeToPipe = universe.pipeMap();
		
		var circularDep = !typeToPipe.containsKey(accessor.getDeclaringStruct().getType());
		
		initSizeDescriptor(universe.makeSizeDescriptor(
			nullable(), circularDep,
			(p, inst) -> get(null, inst))
		);
	}
	
	public Collection<StructPipe<ValueType>> getTypePipes(){
		return typeToPipe.values();
	}
	
	@Override
	public void init(FieldSet<CTyp> ioFields){
		super.init(ioFields);
		universeID = ioFields.requireExactInt(FieldNames.universeID(getAccessor()));
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
				var val = get(ioPool, instance);
				if(val == null){
					var id = getUniverseID(ioPool, instance);
					return id != 0;
				}
				return true;
			}
			@Override
			public Integer generate(VarPool<CTyp> ioPool, DataProvider provider, CTyp instance, boolean allowExternalMod) throws IOException{
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
		var id = getUniverseID(ioPool, instance);
		if(nullable()){
			boolean isNull = id<=0 || getIsNull(ioPool, instance);
			if(isNull){
				return null;
			}
		}
		
		var type         = provider.getTypeDb().fromID(rootType, id);
		var instancePipe = typeToPipe.get(type);
		
		return instancePipe.readNew(provider, src, makeContext(genericContext));
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
		
		instancePipe.skip(provider, src, makeContext(genericContext));
	}
}
