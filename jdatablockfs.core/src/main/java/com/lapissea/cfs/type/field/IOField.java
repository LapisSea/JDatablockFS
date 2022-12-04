package com.lapissea.cfs.type.field;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.exceptions.FieldIsNullException;
import com.lapissea.cfs.exceptions.FixedFormatNotSupportedException;
import com.lapissea.cfs.io.IO;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.objects.Stringify;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.VarPool;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.cfs.type.field.access.VirtualAccessor;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.util.Nullable;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static com.lapissea.cfs.internal.StatIOField.*;
import static com.lapissea.cfs.type.field.FieldSupport.STAT_LOGGING;
import static com.lapissea.cfs.type.field.annotations.IONullability.Mode.DEFAULT_IF_NULL;

public abstract class IOField<T extends IOInstance<T>, ValueType> implements IO<T>, Stringify{
	
	private final FieldAccessor<T> accessor;
	
	private boolean     lateDataInitialized;
	private FieldSet<T> dependencies;
	
	private IONullability.Mode nullability;
	
	public static final int DYNAMIC_FLAG           = 1<<0;
	public static final int IOINSTANCE_FLAG        = 1<<1;
	public static final int PRIMITIVE_OR_ENUM_FLAG = 1<<2;
	public static final int HAS_NO_POINTERS_FLAG   = 1<<3;
	public static final int HAS_GENERATED_NAME     = 1<<4;
	
	private int typeFlags = -1;
	
	private volatile long uid = -1;
	
	public IOField(FieldAccessor<T> accessor){
		this.accessor = accessor;
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
	
	public final boolean isNull(VarPool<T> ioPool, T instance){
		if(!getAccessor().canBeNull()) return false;
		try{
			var val = get(ioPool, instance);
			return val == null;
		}catch(FieldIsNullException npe){
			if(npe.field == this){
				return true;
			}else{
				throw npe;
			}
		}
	}
	
	protected final ValueType getNullable(VarPool<T> ioPool, T instance, Supplier<ValueType> createDefaultIfNull){
		var value = rawGet(ioPool, instance);
		if(value != null) return value;
		return switch(getNullability()){
			case NOT_NULL -> throw new FieldIsNullException(this);
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
			case NOT_NULL -> throw new FieldIsNullException(this);
			case null, NULLABLE -> { }
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
	
	public final SizeDescriptor<T> sizeDescriptorSafe(){
		var struct = declaringStruct();
		if(struct != null){
			struct.waitForState(Struct.STATE_INIT_FIELDS);
		}
		return getSizeDescriptor();
	}
	
	public abstract SizeDescriptor<T> getSizeDescriptor();
	
	public interface ValueGenerator<T extends IOInstance<T>, ValType>{
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
	public List<ValueGeneratorInfo<T, ?>> getGenerators(){
		return null;
	}
	
	public final Stream<ValueGeneratorInfo<T, ?>> generatorStream(){
		var gens = getGenerators();
		return gens == null? Stream.of() : gens.stream();
	}
	
	
	public final void writeReported(VarPool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
		try{
			if(STAT_LOGGING) logStart(WRITE_ACTION, uid());
			write(ioPool, provider, dest, instance);
			if(STAT_LOGGING) logEnd(WRITE_ACTION, uid());
		}catch(VaryingSize.TooSmall e){
			throw e;
		}catch(Exception e){
			throw new IOException("Failed to write " + this, e);
		}
	}
	
	public final void readReported(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		try{
			if(STAT_LOGGING) logStart(READ_ACTION, uid());
			read(ioPool, provider, src, instance, genericContext);
			if(STAT_LOGGING) logEnd(READ_ACTION, uid());
		}catch(Exception e){
			throw new IOException("Failed to read " + this, e);
		}
	}
	
	public final void skipReported(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		try{
			if(STAT_LOGGING) logStart(SKIP_READ_ACTION, uid());
			skip(ioPool, provider, src, instance, genericContext);
			if(STAT_LOGGING) logEnd(SKIP_READ_ACTION, uid());
		}catch(Exception e){
			throw new IOException("Failed to skip read " + this, e);
		}
	}
	
	/**
	 * @return string of the resolved value or no value if string has no substance
	 */
	public Optional<String> instanceToString(VarPool<T> ioPool, T instance, boolean doShort){
		return instanceToString(ioPool, instance, doShort, "{", "}", "=", ", ");
	}
	
	/**
	 * @return string of the resolved value or no value if string has no substance
	 */
	public Optional<String> instanceToString(VarPool<T> ioPool, T instance, boolean doShort, String start, String end, String fieldValueSeparator, String fieldSeparator){
		return FieldSupport.instanceToString(this, ioPool, instance, doShort, start, end, fieldValueSeparator, fieldSeparator);
	}
	
	public boolean instancesEqual(VarPool<T> ioPool1, T inst1, VarPool<T> ioPool2, T inst2){
		return FieldSupport.compare(this, ioPool1, inst1, ioPool2, inst2);
	}
	
	public int instanceHashCode(VarPool<T> ioPool, T instance){
		return FieldSupport.hash(this, ioPool, instance);
	}
	
	public void init(){
		if(getAccessor() instanceof VirtualAccessor<T> vacc) vacc.init(this);
	}
	
	public final long uid(){
		if(uid == -1){
			synchronized(this){
				if(uid == -1){
					uid = FieldSupport.nextUID();
					if(STAT_LOGGING) logRegister(uid, this);
				}
			}
		}
		return uid;
	}
	
	public String getName()                    { return getAccessor().getName(); }
	public Class<?> getType()                  { return getAccessor().getType(); }
	public final FieldAccessor<T> getAccessor(){ return accessor; }
	public final Struct<T> declaringStruct(){
		var acc = getAccessor();
		return acc == null? null : acc.getDeclaringStruct();
	}
	
	private void requireLateData(){
		if(!lateDataInitialized){
			throw new IllegalStateException(this.getName() + " late data not initialized");
		}
	}
	
	@Nullable
	public final FieldSet<T> getDependencies(){
		requireLateData();
		return dependencies;
	}
	
	public final Stream<IOField<T, ?>> dependencyStream(){
		var d = getDependencies();
		return d != null? d.stream() : Stream.of();
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
	public Stream<? extends IOField<T, ?>> streamUnpackedFields(){
		return Stream.of(this);
	}
	
	protected void throwInformativeFixedSizeError(){ }
	private FixedFormatNotSupportedException unsupportedFixed(){
		try{
			throwInformativeFixedSizeError();
		}catch(Throwable e){
			return new FixedFormatNotSupportedException(this, e);
		}
		return new FixedFormatNotSupportedException(this);
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
			f.init();
		}
		if(!f.getSizeDescriptor().hasFixed()) throw new RuntimeException(this + " failed to make itself fixed");
		return f;
	}
	
	
	protected IOField<T, ValueType> maxAsFixedSize(VaryingSize.Provider varProvider){
		throw unsupportedFixed();
	}
	
	public final IONullability.Mode getNullability(){
		if(nullability == null) calcNullability();
		return nullability;
	}
	private void calcNullability(){
		nullability = accessor == null? IONullability.Mode.NULLABLE : IOFieldTools.getNullability(accessor);
	}
	
	public final boolean nullable(){
		return getNullability() == IONullability.Mode.NULLABLE;
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
	public final int hashCode(){
		return getName().hashCode();
	}
}
