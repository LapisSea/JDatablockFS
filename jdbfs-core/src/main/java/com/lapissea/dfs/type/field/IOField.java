package com.lapissea.dfs.type.field;

import com.lapissea.dfs.Utils;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.exceptions.FieldIsNull;
import com.lapissea.dfs.exceptions.FixedFormatNotSupported;
import com.lapissea.dfs.io.IO;
import com.lapissea.dfs.io.content.ContentWriter;
import com.lapissea.dfs.objects.Stringify;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.GetAnnotation;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.field.access.AnnotatedType;
import com.lapissea.dfs.type.field.access.FieldAccessor;
import com.lapissea.dfs.type.field.access.VirtualAccessor;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.fields.BitField;
import com.lapissea.dfs.type.field.fields.NoIOField;
import com.lapissea.dfs.type.field.fields.NullFlagCompanyField;
import com.lapissea.dfs.type.field.fields.RefField;
import com.lapissea.dfs.type.field.fields.reflection.BitFieldMerger;
import com.lapissea.dfs.type.field.fields.reflection.IOFieldChunkPointer;
import com.lapissea.dfs.type.field.fields.reflection.IOFieldOptional;
import com.lapissea.dfs.type.field.fields.reflection.IOFieldPrimitive;
import com.lapissea.dfs.type.string.StringifySettings;
import com.lapissea.dfs.utils.iterableplus.IterablePP;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.NotNull;
import com.lapissea.util.Nullable;
import com.lapissea.util.UtilL;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.lapissea.dfs.type.field.annotations.IONullability.Mode.DEFAULT_IF_NULL;

public abstract sealed class IOField<T extends IOInstance<T>, ValueType> implements IO<T>, Stringify, AnnotatedType
	permits BitField, NoIOField, NullFlagCompanyField, RefField, BitFieldMerger, IOFieldChunkPointer, IOFieldOptional, IOFieldPrimitive{
	
	public interface FieldUsage{
		abstract class InstanceOf<Typ> implements FieldUsage{
			private final Class<Typ>                    type;
			@SuppressWarnings("rawtypes")
			private final Set<Class<? extends IOField>> fieldTypes;
			@SuppressWarnings("rawtypes")
			public InstanceOf(Class<Typ> type, Set<Class<? extends IOField>> fieldTypes){
				this.type = type;
				this.fieldTypes = Set.copyOf(fieldTypes);
			}
			
			public final Class<Typ> getType(){
				return type;
			}
			
			@Override
			public final boolean isCompatible(Type type, GetAnnotation annotations){
				return UtilL.instanceOf(Utils.typeToRaw(type), getType());
			}
			@Override
			public abstract <T extends IOInstance<T>> IOField<T, Typ> create(FieldAccessor<T> field);
			@Override
			@SuppressWarnings("rawtypes")
			public final Set<Class<? extends IOField>> listFieldTypes(){ return fieldTypes; }
		}
		
		boolean isCompatible(Type type, GetAnnotation annotations);
		<T extends IOInstance<T>> IOField<T, ?> create(FieldAccessor<T> field);
		@SuppressWarnings("rawtypes")
		Set<Class<? extends IOField>> listFieldTypes();
		
		record BehaviourRes<T extends IOInstance<T>>(List<VirtualFieldDefinition<T, ?>> fields, Set<Class<? extends Annotation>> touchedAnnotations){
			public BehaviourRes(VirtualFieldDefinition<T, ?> field)       { this(List.of(field)); }
			public BehaviourRes(List<VirtualFieldDefinition<T, ?>> fields){ this(fields, Set.of()); }
			public BehaviourRes(List<VirtualFieldDefinition<T, ?>> fields, Set<Class<? extends Annotation>> touchedAnnotations){
				this.fields = List.copyOf(fields);
				this.touchedAnnotations = Set.copyOf(touchedAnnotations);
			}
			
			private static final BehaviourRes<?> NON = new BehaviourRes<>(List.of(), Set.of());
			@SuppressWarnings("unchecked")
			public static <T extends IOInstance<T>> BehaviourRes<T> non(){ return (BehaviourRes<T>)NON; }
		}
		
		record Behaviour<A extends Annotation, T extends IOInstance<T>>(
			Class<A> annotationType,
			BiFunction<FieldAccessor<T>, A, BehaviourRes<T>> generateFields,
			Optional<BiFunction<FieldAccessor<T>, A, Set<String>>> dependencyNames
		){
			public static <A extends Annotation, T extends IOInstance<T>> Behaviour<A, T> noop(Class<A> annotationType){
				return new Behaviour<>(annotationType, (f, a) -> BehaviourRes.non(), Optional.empty());
			}
			
			public static <A extends Annotation, T extends IOInstance<T>>
			Behaviour<A, T> of(Class<A> annotationType, Function<FieldAccessor<T>, BehaviourRes<T>> generateFields){
				return new Behaviour<>(annotationType, (f, a) -> generateFields.apply(f), Optional.empty());
			}
			public static <A extends Annotation, T extends IOInstance<T>>
			Behaviour<A, T> of(Class<A> annotationType, BiFunction<FieldAccessor<T>, A, BehaviourRes<T>> generateFields){
				return new Behaviour<>(annotationType, generateFields, Optional.empty());
			}
			
			public static <A extends Annotation, T extends IOInstance<T>>
			Behaviour<A, T> justDeps(Class<A> annotationType, BiFunction<FieldAccessor<T>, A, Set<String>> dependencyNames){
				return Behaviour.<A, T>noop(annotationType).withDeps(dependencyNames);
			}
			
			public static <A extends Annotation, T extends IOInstance<T>>
			Behaviour<A, T> justDeps(Class<A> annotationType, Function<A, Set<String>> dependencyNames){
				return Behaviour.<A, T>noop(annotationType).withDeps((f, a) -> dependencyNames.apply(a));
			}
			
			public Behaviour<A, T> withDeps(BiFunction<FieldAccessor<T>, A, Set<String>> dependencyNames){
				return new Behaviour<>(annotationType, generateFields, Optional.of(dependencyNames));
			}
			
			public Optional<BehaviourRes<T>> generateFields(FieldAccessor<T> accessor){
				return accessor.getAnnotation(annotationType).map(a -> generateFields.apply(accessor, a));
			}
			public Optional<Set<String>> getDependencyNames(FieldAccessor<T> accessor){
				return accessor.getAnnotation(annotationType).map(ann -> {
					if(dependencyNames.isPresent()){
						return dependencyNames.get().apply(accessor, ann);
					}
					var fields = generateFields.apply(accessor, ann).fields;
					return Iters.from(fields).map(VirtualFieldDefinition::name).toModSet();
				});
			}
		}
		
		<T extends IOInstance<T>> List<Behaviour<?, T>> annotationBehaviour(Class<IOField<T, ?>> fieldType);
	}
	
	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.TYPE)
	public @interface FieldUsageRef{
		Class<FieldUsage>[] value();
	}
	
	private final FieldAccessor<T> accessor;
	
	private boolean     lateDataInitialized;
	private FieldSet<T> dependencies;
	
	private IONullability.Mode nullability;
	
	private SizeDescriptor<T> descriptor;
	
	public static final int DYNAMIC_FLAG           = 1<<0;
	public static final int IOINSTANCE_FLAG        = 1<<1;
	public static final int PRIMITIVE_OR_ENUM_FLAG = 1<<2;
	public static final int HAS_NO_POINTERS_FLAG   = 1<<3;
	public static final int HAS_GENERATED_NAME     = 1<<4;
	
	private int  typeFlags   = -1;
	private byte needsIOPool = 2;
	
	private final int hashCode;
	
	protected IOField(FieldAccessor<T> accessor, SizeDescriptor<T> descriptor){
		this.accessor = accessor;
		hashCode = calcHashCode(accessor);
		initSizeDescriptor(descriptor);
	}
	public IOField(FieldAccessor<T> accessor){
		this.accessor = accessor;
		hashCode = calcHashCode(accessor);
	}
	
	private int calcHashCode(FieldAccessor<T> accessor){
		if(accessor == null) return System.identityHashCode(this);
		return accessor.getName().hashCode();
	}
	
	public final void initLateData(FieldSet<T> dependencies){
		if(lateDataInitialized) throw new IllegalStateException("already initialized");
		
		this.dependencies = dependencies == null? null : Utils.nullIfEmpty(dependencies);
		lateDataInitialized = true;
	}
	
	public final boolean typeFlag(int flag){
		return (typeFlags()&flag) == flag;
	}
	
	public final int typeFlags(){
		var f = typeFlags;
		if(f == -1) f = typeFlags = FieldSupport.typeFlags(this);
		return f;
	}
	
	public boolean isNull(VarPool<T> ioPool, T instance){
		if(!getAccessor().canBeNull()) return false;
		try{
			var val = get(ioPool, instance);
			return val == null;
		}catch(FieldIsNull npe){
			if(npe.field == this){
				return true;
			}else{
				throw npe;
			}
		}
	}
	
	protected final boolean isNullRawNullable(VarPool<T> ioPool, T instance){
		return switch(getNullability()){
			case NOT_NULL, NULLABLE -> rawGet(ioPool, instance) == null;
			case DEFAULT_IF_NULL -> false;
		};
	}
	
	protected final ValueType getNullable(VarPool<T> ioPool, T instance, Supplier<ValueType> createDefaultIfNull){
		var value = rawGet(ioPool, instance);
		if(value != null) return value;
		return switch(getNullability()){
			case NOT_NULL -> throw new FieldIsNull(this);
			case NULLABLE -> null;
			case DEFAULT_IF_NULL -> {
				var newVal = createDefaultIfNull.get();
				set(ioPool, instance, newVal);
				yield newVal;
			}
		};
	}
	
	protected final ValueType getNullable(VarPool<T> ioPool, T instance){
		var value = rawGet(ioPool, instance);
		if(value != null) return value;
		switch(getNullability()){
			case NOT_NULL -> throw new FieldIsNull(this);
			case NULLABLE -> { }
			case null -> { }
			case DEFAULT_IF_NULL -> throw new IllegalStateException(this + " does not support " + DEFAULT_IF_NULL);
		}
		return null;
	}
	
	public ValueType get(VarPool<T> ioPool, T instance){
		return getNullable(ioPool, instance);
	}
	
	@SuppressWarnings("unchecked")
	protected final ValueType rawGet(VarPool<T> ioPool, T instance){
		return (ValueType)getAccessor().get(ioPool, instance);
	}
	
	public void set(VarPool<T> ioPool, T instance, ValueType value){
		getAccessor().set(ioPool, instance, value);
	}
	
	protected final void initSizeDescriptor(SizeDescriptor<T> descriptor){
		Objects.requireNonNull(descriptor);
		if(this.descriptor != null) throw new IllegalStateException("Descriptor already set");
		this.descriptor = descriptor;
	}
	
	public final SizeDescriptor<T> sizeDescriptorSafe(){
		var d = descriptor;
		if(d != null) return d;
		return blockingGet();
	}
	private SizeDescriptor<T> blockingGet(){
		var struct = declaringStruct();
		if(struct != null){
			struct.waitForState(Struct.STATE_INIT_FIELDS);
		}
		return descriptor;
	}
	
	public final SizeDescriptor<T> getSizeDescriptor(){
		return descriptor;
	}
	
	public interface ValueGenerator<T extends IOInstance<T>, ValType>{
		enum Strictness{
			NOT_REALLY, ON_EXTERNAL_ALWAYS, ALWAYS
		}
		default Strictness strictDetermineLevel(){ return Strictness.ALWAYS; }
		
		boolean shouldGenerate(VarPool<T> ioPool, DataProvider provider, T instance) throws IOException;
		ValType generate(VarPool<T> ioPool, DataProvider provider, T instance, boolean allowExternalMod) throws IOException;
	}
	
	public record ValueGeneratorInfo<T extends IOInstance<T>, ValType>(
		IOField<T, ValType> field,
		ValueGenerator<T, ValType> generator
	) implements Stringify{
		public void generate(VarPool<T> ioPool, DataProvider provider, T instance, boolean allowExternalMod) throws IOException{
			if(generator.shouldGenerate(ioPool, provider, instance)){
				var val = generator.generate(ioPool, provider, instance, allowExternalMod);
				field.set(ioPool, instance, val);
			}
		}
		@Override
		public String toString(){
			return ValueGeneratorInfo.class.getSimpleName() + "{modifies " + field + "}";
		}
		@Override
		public String toShortString(){
			return "{mod " + Utils.toShortString(field) + "}";
		}
	}
	
	@Nullable
	@NotNull
	public List<ValueGeneratorInfo<T, ?>> getGenerators(){
		return List.of();
	}
	
	public final void writeReported(VarPool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
		try{
			write(ioPool, provider, dest, instance);
		}catch(IOException e){
			e.addSuppressed(new IOException("Failed to write " + this));
			throw e;
		}
	}
	
	/**
	 * @return string of the resolved value or no value if string has no substance
	 */
	public Optional<String> instanceToString(VarPool<T> ioPool, T instance, boolean doShort){
		return instanceToString(ioPool, instance, StringifySettings.ofDoShort(doShort));
	}
	
	/**
	 * @return string of the resolved value or no value if string has no substance
	 */
	public Optional<String> instanceToString(VarPool<T> ioPool, T instance, StringifySettings settings){
		return FieldSupport.instanceToString(this, ioPool, instance, settings);
	}
	
	public boolean instancesEqual(VarPool<T> ioPool1, T inst1, VarPool<T> ioPool2, T inst2){
		return FieldSupport.compare(this, ioPool1, inst1, ioPool2, inst2);
	}
	
	public int instanceHashCode(VarPool<T> ioPool, T instance){
		return FieldSupport.hash(this, ioPool, instance);
	}
	
	public void init(FieldSet<T> fields){
		if(getAccessor() instanceof VirtualAccessor<T> vacc) vacc.init(this);
	}
	
	public String getName(){ return getAccessor().getName(); }
	@SuppressWarnings("unchecked")
	@Override
	public final Class<? extends ValueType> getType(){ return (Class<? extends ValueType>)getAccessor().getType(); }
	public final FieldAccessor<T> getAccessor(){ return accessor; }
	public final Struct<T> declaringStruct(){
		var acc = getAccessor();
		return acc == null? null : acc.getDeclaringStruct();
	}
	public final boolean isVirtual(){
		return getAccessor() instanceof VirtualAccessor;
	}
	
	public final boolean isVirtual(StoragePool pool){
		return getAccessor() instanceof VirtualAccessor<?> acc &&
		       (pool == null || acc.getStoragePool() == pool);
	}
	public final Optional<VirtualAccessor<T>> getVirtual(StoragePool pool){
		if(getAccessor() instanceof VirtualAccessor<T> acc){
			if(pool == null || acc.getStoragePool() == pool){
				return Optional.of(acc);
			}
		}
		return Optional.empty();
	}
	
	public GenericContext makeContext(GenericContext parent){
		var acc = getAccessor();
		if(parent == null && acc.genericTypeHasArgs()){
			return null;
		}
		var raw = acc.getType();
		if(raw == Object.class) return null;
		var type = acc.getGenericType(parent);
		return GenericContext.of(raw, type);
	}
	
	private void requireLateData(){
		if(!lateDataInitialized){
			throw new IllegalStateException(this.getName() + " late data not initialized");
		}
	}
	
	@NotNull
	public final FieldSet<T> getDependencies(){
		requireLateData();
		var d = dependencies;
		return d == null? FieldSet.of() : dependencies;
	}
	
	public final boolean isDependency(IOField<T, ?> depField){
		requireLateData();
		return dependencies != null && dependencies.contains(depField);
	}
	
	public final boolean hasDependencies(){
		requireLateData();
		assert dependencies == null || !dependencies.isEmpty();
		return dependencies != null;
	}
	
	@Override
	public String toShortString(){
		return Objects.requireNonNull(getName());
	}
	@Override
	public String toString(){
		var struct = getAccessor().getDeclaringStruct();
		return (struct == null? "" : struct.cleanName()) + "#" + toShortString();
	}
	
	
	/**
	 * @return a stream of fields that are directly referenced by the struct. (field that represents a group of fields should return the containing fields)
	 */
	public IterablePP<IOField<T, ?>> iterUnpackedFields(){
		return Iters.of(this);
	}
	
	protected void throwInformativeFixedSizeError(){ }
	private FixedFormatNotSupported unsupportedFixed(){
		try{
			throwInformativeFixedSizeError();
		}catch(Throwable e){
			return new FixedFormatNotSupported(this, e);
		}
		return new FixedFormatNotSupported(this);
	}
	
	public final IOField<T, ValueType> forceMaxAsFixedSize(){
		return forceMaxAsFixedSize(null);
	}
	public final IOField<T, ValueType> forceMaxAsFixedSize(VaryingSize.Provider provider){
		if(provider == null && getSizeDescriptor().hasFixed()) return this;
		if(!getSizeDescriptor().hasMax()){
			throw unsupportedFixed();
		}
		var f = maxAsFixedSize(provider == null? VaryingSize.Provider.ALL_MAX : provider);
		if(f != this){
			f.initLateData(getDependencies());
			var struct = declaringStruct();
			f.init(struct == null? null : struct.getFields());
			Objects.requireNonNull(f.getSizeDescriptor(), "Descriptor was not inited");
		}
		if(!f.getSizeDescriptor().hasFixed()) throw new RuntimeException(this + " failed to make itself fixed");
		return f;
	}
	
	
	protected IOField<T, ValueType> maxAsFixedSize(VaryingSize.Provider varProvider){
		throw unsupportedFixed();
	}
	
	public final IONullability.Mode getNullability(){
		var n = nullability;
		if(n != null) return n;
		return calcNullability();
	}
	private IONullability.Mode calcNullability(){
		return nullability = getAccessor() == null? IONullability.Mode.NULLABLE : IOFieldTools.getNullability(getAccessor());
	}
	
	public final boolean nullable(){
		return getNullability() == IONullability.Mode.NULLABLE;
	}
	public final boolean isNonNullable(){
		return getNullability() == IONullability.Mode.NOT_NULL;
	}
	
	@Override
	public final Map<Class<? extends Annotation>, ? extends Annotation> getAnnotations(){
		var acc = getAccessor();
		if(acc == null) return Map.of();
		return acc.getAnnotations();
	}
	
	@Override
	public final Type getGenericType(GenericContext genericContext){
		return getAccessor().getGenericType(genericContext);
	}
	
	@Override
	public boolean equals(Object o){
		if(this == o) return true;
		if(!(o instanceof IOField<?, ?> ioField)) return false;
		
		var acc = getAccessor();
		if(acc == null){
			if(ioField.getAccessor() != null) return false;
			return getName().equals(ioField.getName());
		}
		return acc.equals(ioField.getAccessor());
	}
	@Override
	public final int hashCode(){ return hashCode; }
	
	public boolean needsIOPool(){
		if(needsIOPool == 2) needsIOPool = calcNeedsIOPool()? (byte)1 : 0;
		return needsIOPool == 1;
	}
	private boolean calcNeedsIOPool(){
		final var depSet  = new HashSet<IOField<T, ?>>(Set.of(this));
		final var scanned = new HashSet<IOField<T, ?>>();
		final var toAdd   = new ArrayList<Collection<IOField<T, ?>>>();
		var       change  = true;
		while(change){
			change = false;
			for(var dep : depSet){
				if(scanned.contains(dep)) continue;
				
				if(dep.isVirtual(StoragePool.IO)){
					return true;
				}
				if(dep.hasDependencies()){
					toAdd.add(dep.getDependencies());
				}
				scanned.add(dep);
			}
			for(var col : toAdd){
				change |= depSet.addAll(col);
			}
			toAdd.clear();
		}
		return false;
	}
}
