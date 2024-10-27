package com.lapissea.dfs.type.field.fields.reflection;

import com.lapissea.dfs.Utils;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.exceptions.MalformedStruct;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.io.content.ContentWriter;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.compilation.FieldCompiler;
import com.lapissea.dfs.type.field.Annotations;
import com.lapissea.dfs.type.field.FieldNames;
import com.lapissea.dfs.type.field.FieldSet;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.IOFieldTools;
import com.lapissea.dfs.type.field.SizeDescriptor;
import com.lapissea.dfs.type.field.VirtualFieldDefinition;
import com.lapissea.dfs.type.field.access.FieldAccessor;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.utils.iterableplus.Iters;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.lapissea.dfs.type.field.StoragePool.IO;

public final class IOFieldOptional<T extends IOInstance<T>, V> extends IOField<T, Optional<V>>{
	
	@SuppressWarnings("unused")
	private static final class Usage<V> extends FieldUsage.InstanceOf<Optional<V>>{
		@SuppressWarnings("unchecked")
		public Usage(){ super((Class<Optional<V>>)(Object)Optional.class, Set.of(IOFieldOptional.class)); }
		@Override
		public <T extends IOInstance<T>> IOField<T, Optional<V>> create(FieldAccessor<T> field){
			return new IOFieldOptional<>(field);
		}
		@Override
		public <T extends IOInstance<T>> List<Behaviour<?, T>> annotationBehaviour(Class<IOField<T, ?>> fieldType){
			return List.of(
				Behaviour.of(IOValue.class, (field, ann) -> {
					if(field.getAnnotation(IONullability.class).map(IONullability::value)
					        .orElse(IONullability.Mode.NOT_NULL) != IONullability.Mode.NOT_NULL){
						throw new MalformedStruct("Optional fields can not have nullability");
					}
					
					var anns = new HashSet<>(FieldCompiler.ANNOTATION_TYPES);
					anns.remove(IOValue.class);
					anns.remove(IONullability.class);
					
					var annotations = Iters.concat(
						Iters.of(Annotations.make(IOValue.class), Annotations.makeNullability(IONullability.Mode.NULLABLE)),
						Iters.from(anns).flatOptionals(field::getAnnotation)
					).toList();
					
					var genType = field.getGenericType(null);
					
					var type = IOFieldTools.unwrapOptionalType(genType).orElseThrow(() -> {
						return new MalformedStruct("fmt", "Illegal type of: {}#red for optional field", field.getGenericType(null).getTypeName());
					});
					
					return new BehaviourRes<>(List.of(new VirtualFieldDefinition<T, Integer>(
						IO,
						FieldNames.companionValueFlag(field),
						type,
						annotations
					)), Iters.from(annotations).toSet(Annotation::annotationType));
				})
			);
		}
		
	}
	
	private IOField<T, V> valueField;
	
	public IOFieldOptional(FieldAccessor<T> accessor){
		super(accessor);
		initSizeDescriptor(SizeDescriptor.Fixed.empty());
	}
	@Override
	protected Set<TypeFlag> computeTypeFlags(){
		return Set.of(TypeFlag.HAS_NO_POINTERS);
	}
	
	@Override
	public void init(FieldSet<T> fields){
		super.init(fields);
		//noinspection unchecked
		valueField = (IOField<T, V>)fields.requireByName(FieldNames.companionValueFlag(getAccessor()));
	}
	
	@Override
	public List<ValueGeneratorInfo<T, ?>> getGenerators(){
		return Utils.concat(super.getGenerators(), new ValueGeneratorInfo<>(valueField, new ValueGenerator<>(){
			@Override
			public boolean shouldGenerate(VarPool<T> ioPool, DataProvider provider, T instance){
				V val      = valueField.get(ioPool, instance);
				V existing = get(ioPool, instance).orElse(null);
				return val != existing;
			}
			@Override
			public V generate(VarPool<T> ioPool, DataProvider provider, T instance, boolean allowExternalMod){
				var opt = get(ioPool, instance);
				return opt.orElse(null);
			}
		}));
	}
	@Override
	public void write(VarPool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
		//Noop value field is written
	}
	@Override
	public void read(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		var value = valueField.get(ioPool, instance);
		set(ioPool, instance, Optional.ofNullable(value));
	}
	
	@Override
	public void skip(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		//Noop value field is skipped
	}
}
