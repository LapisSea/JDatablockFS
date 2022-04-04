package com.lapissea.cfs.type.field;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.exceptions.FieldIsNullException;
import com.lapissea.cfs.exceptions.FixedFormatNotSupportedException;
import com.lapissea.cfs.io.bit.BitInputStream;
import com.lapissea.cfs.io.bit.BitOutputStream;
import com.lapissea.cfs.io.bit.BitReader;
import com.lapissea.cfs.io.bit.BitWriter;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.*;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.cfs.type.field.annotations.IOType;
import com.lapissea.cfs.type.field.fields.reflection.IOFieldPrimitive;
import com.lapissea.util.*;

import java.io.IOException;
import java.lang.reflect.Array;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static com.lapissea.cfs.GlobalConfig.DEBUG_VALIDATION;
import static com.lapissea.cfs.type.field.VirtualFieldDefinition.StoragePool.IO;

public abstract class IOField<T extends IOInstance<T>, ValueType>{
	
	static{
		TextUtil.SHORT_TO_STRINGS.register(OptionalLong.class, l->l.isEmpty()?"()L":"("+l.getAsLong()+")L");
	}
	
	
	public enum UsageHintType{
		SIZE_DATA
	}
	
	public record UsageHintDefinition(UsageHintType type, String target){}
	
	
	public abstract static class FixedSizeDescriptor<Inst extends IOInstance<Inst>, ValueType> extends IOField<Inst, ValueType>{
		
		private final SizeDescriptor<Inst> sizeDescriptor;
		
		public FixedSizeDescriptor(FieldAccessor<Inst> accessor, SizeDescriptor<Inst> sizeDescriptor){
			super(accessor);
			this.sizeDescriptor=sizeDescriptor;
		}
		
		@Override
		public SizeDescriptor<Inst> getSizeDescriptor(){
			return sizeDescriptor;
		}
	}
	
	public static class NoIO<Inst extends IOInstance<Inst>, ValueType> extends IOField<Inst, ValueType>{
		
		private final SizeDescriptor<Inst> sizeDescriptor;
		
		public NoIO(FieldAccessor<Inst> accessor, SizeDescriptor<Inst> sizeDescriptor){
			super(accessor);
			this.sizeDescriptor=sizeDescriptor;
		}
		
		@Override
		public SizeDescriptor<Inst> getSizeDescriptor(){
			return sizeDescriptor;
		}
		
		@Override
		public void write(Struct.Pool<Inst> ioPool, DataProvider provider, ContentWriter dest, Inst instance) throws IOException{
			throw new UnsupportedOperationException();
		}
		@Override
		public void read(Struct.Pool<Inst> ioPool, DataProvider provider, ContentReader src, Inst instance, GenericContext genericContext) throws IOException{
			throw new UnsupportedOperationException();
		}
		@Override
		public void skipRead(Struct.Pool<Inst> ioPool, DataProvider provider, ContentReader src, Inst instance, GenericContext genericContext) throws IOException{
			throw new UnsupportedOperationException();
		}
	}
	
	public abstract static class Ref<T extends IOInstance<T>, Type extends IOInstance<Type>> extends IOField<T, Type>{
		
		public abstract static class NoIO<T extends IOInstance<T>, ValueType extends IOInstance<ValueType>> extends IOField.Ref<T, ValueType>{
			
			private final SizeDescriptor<T> sizeDescriptor;
			
			public NoIO(FieldAccessor<T> accessor, SizeDescriptor<T> sizeDescriptor){
				super(accessor);
				this.sizeDescriptor=sizeDescriptor;
			}
			
			@Override
			public SizeDescriptor<T> getSizeDescriptor(){
				return sizeDescriptor;
			}
			
			@Override
			public void write(Struct.Pool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
				throw new UnsupportedOperationException();
			}
			@Override
			public void read(Struct.Pool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
				throw new UnsupportedOperationException();
			}
			@Override
			public void skipRead(Struct.Pool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
				throw new UnsupportedOperationException();
			}
			@Override
			public void allocate(T instance, DataProvider provider, GenericContext genericContext) throws IOException{
				throw new UnsupportedOperationException();
			}
		}
		
		public Ref(FieldAccessor<T> accessor){
			super(accessor);
		}
		
		public void allocateUnmanaged(T instance) throws IOException{
			IOInstance.Unmanaged<?> unmanaged=(IOInstance.Unmanaged<?>)instance;
			allocate(instance, unmanaged.getDataProvider(), unmanaged.getGenerics());
		}
		
		public abstract void allocate(T instance, DataProvider provider, GenericContext genericContext) throws IOException;
		public abstract void setReference(T instance, Reference newRef);
		public abstract Reference getReference(T instance);
		public abstract StructPipe<Type> getReferencedPipe(T instance);
		
		@Override
		public IOField.Ref<T, Type> implMaxAsFixedSize(){
			throw new NotImplementedException();
		}
	}
	
	public abstract static class Bit<T extends IOInstance<T>, Type> extends IOField<T, Type>{
		
		protected Bit(FieldAccessor<T> field){
			super(field);
		}
		
		@Deprecated
		@Override
		public final void write(Struct.Pool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
			try(var writer=new BitOutputStream(dest)){
				writeBits(ioPool, writer, instance);
				if(DEBUG_VALIDATION){
					writer.requireWritten(getSizeDescriptor().calcUnknown(ioPool, provider, instance, WordSpace.BIT));
				}
			}
		}
		
		@Deprecated
		@Override
		public final void read(Struct.Pool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
			try(var reader=new BitInputStream(src, getSizeDescriptor().getFixed(WordSpace.BIT).orElse(-1))){
				readBits(ioPool, reader, instance);
				if(DEBUG_VALIDATION){
					reader.requireRead(getSizeDescriptor().calcUnknown(ioPool, provider, instance, WordSpace.BIT));
				}
			}
		}
		
		@Deprecated
		@Override
		public final void skipRead(Struct.Pool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
			if(src.optionallySkipExact(getSizeDescriptor().getFixed(WordSpace.BYTE))){
				return;
			}
			
			try(var reader=new BitInputStream(src, -1)){
				skipReadBits(reader, instance);
				if(DEBUG_VALIDATION){
					reader.requireRead(getSizeDescriptor().calcUnknown(ioPool, provider, instance, WordSpace.BIT));
				}
			}
		}
		
		public abstract void writeBits(Struct.Pool<T> ioPool, BitWriter<?> dest, T instance) throws IOException;
		public abstract void readBits(Struct.Pool<T> ioPool, BitReader src, T instance) throws IOException;
		public abstract void skipReadBits(BitReader src, T instance) throws IOException;
		
		@Override
		public IOField.Bit<T, Type> implMaxAsFixedSize(){
			throw new NotImplementedException();
		}
	}
	
	public abstract static class NullFlagCompany<T extends IOInstance<T>, Type> extends IOField<T, Type>{
		
		private IOFieldPrimitive.FBoolean<T> isNull;
		
		protected NullFlagCompany(FieldAccessor<T> field){
			super(field);
		}
		
		@Override
		public void init(){
			super.init();
			if(nullable()){
				isNull=declaringStruct().getFields().requireExactBoolean(IOFieldTools.makeNullFlagName(getAccessor()));
			}
		}
		
		@Override
		public List<ValueGeneratorInfo<T, ?>> getGenerators(){
			
			if(!nullable()) return List.of();
			
			return List.of(new ValueGeneratorInfo<>(isNull, new ValueGenerator<T, Boolean>(){
				@Override
				public boolean shouldGenerate(Struct.Pool<T> ioPool, DataProvider provider, T instance){
					var isNullRec    =get(ioPool, instance)==null;
					var writtenIsNull=isNull.getValue(ioPool, instance);
					return writtenIsNull!=isNullRec;
				}
				@Override
				public Boolean generate(Struct.Pool<T> ioPool, DataProvider provider, T instance, boolean allowExternalMod){
					return get(ioPool, instance)==null;
				}
			}));
		}
		
		protected final boolean getIsNull(Struct.Pool<T> ioPool, T instance){
			if(DEBUG_VALIDATION){
				if(!nullable()) throw new RuntimeException("Checking if null on a non nullable field");
			}
			
			return isNull.getValue(ioPool, instance);
		}
		
	}
	
	private final FieldAccessor<T> accessor;
	
	private boolean                lateDataInitialized;
	private FieldSet<T>            dependencies;
	private EnumSet<UsageHintType> usageHints;
	
	private IONullability.Mode nullability;
	
	public static final int DYNAMIC_FLAG          =1<<0;
	public static final int IOINSTANCE_FLAG       =1<<1;
	public static final int PRIMITIVE_OR_ENUM_FLAG=1<<2;
	public static final int HAS_NO_POINTERS_FLAG  =1<<3;
	
	private int typeFlags=-1;
	
	public IOField(FieldAccessor<T> accessor){
		this.accessor=accessor;
	}
	
	public void initLateData(FieldSet<T> dependencies, Stream<UsageHintType> usageHints){
		Utils.requireNull(this.dependencies);
		Utils.requireNull(this.usageHints);
		this.dependencies=dependencies;
		var h=EnumSet.noneOf(UsageHintType.class);
		usageHints.forEach(h::add);
		this.usageHints=Utils.nullIfEmpty(h);
		lateDataInitialized=true;
	}
	
	public int typeFlags(){
		if(typeFlags!=-1){
			return typeFlags;
		}
		
		int typeFlags=0;
		
		if(accessor!=null){
			if(accessor.hasAnnotation(IOType.Dynamic.class)){
				typeFlags|=DYNAMIC_FLAG;
			}
			var type=accessor.getType();
			if(IOInstance.isInstance(type)){
				typeFlags|=IOINSTANCE_FLAG;
				
				if(!(this instanceof IOField.Ref)&&!Struct.ofUnknown(type).getCanHavePointers()){
					typeFlags|=HAS_NO_POINTERS_FLAG;
				}
			}
			if(IOFieldPrimitive.isPrimitive(type)||type.isEnum()){
				typeFlags|=PRIMITIVE_OR_ENUM_FLAG;
			}
		}
		this.typeFlags=typeFlags;
		return typeFlags;
	}
	
	public boolean isNull(Struct.Pool<T> ioPool, T instance){
		if(!getAccessor().canBeNull()) return false;
		try{
			var val=get(ioPool, instance);
			return val==null;
		}catch(FieldIsNullException npe){
			if(npe.field==this){
				return true;
			}else{
				throw npe;
			}
		}
	}
	
	protected final ValueType getNullable(Struct.Pool<T> ioPool, T instance, Supplier<ValueType> createDefaultIfNull){
		var value=get0(ioPool, instance);
		return switch(getNullability()){
			case NOT_NULL -> requireValNN(value);
			case NULLABLE -> value;
			case DEFAULT_IF_NULL -> {
				if(value!=null) yield value;
				var newVal=createDefaultIfNull.get();
				set(ioPool, instance, newVal);
				yield newVal;
			}
		};
	}
	
	protected final ValueType getNullable(Struct.Pool<T> ioPool, T instance){
		var value=get0(ioPool, instance);
		return switch(getNullability()){
			case NOT_NULL -> requireValNN(value);
			case NULLABLE -> value;
			case DEFAULT_IF_NULL -> throw new IllegalStateException(this+" does not support "+IONullability.Mode.DEFAULT_IF_NULL);
		};
	}
	
	public ValueType get(Struct.Pool<T> ioPool, T instance){
		return get0(ioPool, instance);
	}
	@SuppressWarnings("unchecked")
	private ValueType get0(Struct.Pool<T> ioPool, T instance){
		return (ValueType)getAccessor().get(ioPool, instance);
	}
	
	public void set(Struct.Pool<T> ioPool, T instance, ValueType value){
		getAccessor().set(ioPool, instance, value);
	}
	
	public abstract SizeDescriptor<T> getSizeDescriptor();
	
	public interface ValueGenerator<T extends IOInstance<T>, ValType>{
		boolean shouldGenerate(Struct.Pool<T> ioPool, DataProvider provider, T instance) throws IOException;
		ValType generate(Struct.Pool<T> ioPool, DataProvider provider, T instance, boolean allowExternalMod) throws IOException;
	}
	
	public record ValueGeneratorInfo<T extends IOInstance<T>, ValType>(
		IOField<T, ValType> field,
		ValueGenerator<T, ValType> generator
	){
		public void generate(Struct.Pool<T> ioPool, DataProvider provider, T instance, boolean allowExternalMod) throws IOException{
			if(generator.shouldGenerate(ioPool, provider, instance)){
				var val=generator.generate(ioPool, provider, instance, allowExternalMod);
				field.set(ioPool, instance, val);
			}
		}
		@Override
		public String toString(){
			return ValueGeneratorInfo.class.getSimpleName()+"{modifies "+field+"}";
		}
		public String toShortString(){
			return "{mod "+Utils.toShortString(field)+"}";
		}
	}
	
	@NotNull
	public List<ValueGeneratorInfo<T, ?>> getGenerators(){
		return List.of();
	}
	
	@Nullable
	public abstract void write(Struct.Pool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException;
	@Nullable
	public final void writeReported(Struct.Pool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
		try{
			write(ioPool, provider, dest, instance);
		}catch(Exception e){
			throw new IOException("Failed to write "+this, e);
		}
	}
	
	public abstract void read(Struct.Pool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException;
	public final void readReported(Struct.Pool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		String extra="";
		if(DEBUG_VALIDATION){
			extra=" started on: "+src;
		}
		try{
			read(ioPool, provider, src, instance, genericContext);
		}catch(Exception e){
			throw new IOException("Failed to read "+this+extra, e);
		}
	}
	
	public abstract void skipRead(Struct.Pool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException;
	public final void skipReadReported(Struct.Pool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		try{
			skipRead(ioPool, provider, src, instance, genericContext);
		}catch(Exception e){
			throw reportSkipReadFail(this, e);
		}
	}
	protected IOException reportSkipReadFail(IOField<T, ?> fi, Exception e) throws IOException{
		throw new IOException("Failed to skip read "+fi, e);
	}
	
	
	/**
	 * @return string of the resolved value or null if string has no substance
	 */
	public Optional<String> instanceToString(Struct.Pool<T> ioPool, T instance, boolean doShort){
		return instanceToString(ioPool, instance, doShort, "{", "}", "=", ", ");
	}
	
	/**
	 * @return string of the resolved value or null if string has no substance
	 */
	public Optional<String> instanceToString(Struct.Pool<T> ioPool, T instance, boolean doShort, String start, String end, String fieldValueSeparator, String fieldSeparator){
		var val=get(ioPool, instance);
		if(val==null) return Optional.empty();
		
		if(val instanceof IOInstance inst){
			var struct=inst.getThisStruct();
			return Optional.of(struct.instanceToString(struct.allocVirtualVarPool(IO), inst, doShort, start, end, fieldValueSeparator, fieldSeparator));
		}
		if(doShort){
			return Optional.of(Utils.toShortString(val));
		}
		return Optional.of(TextUtil.toString(val));
	}
	
	@SuppressWarnings("unchecked")
	public boolean instancesEqual(Struct.Pool<T> ioPool1, T inst1, Struct.Pool<T> ioPool2, T inst2){
		var o1=get(ioPool1, inst1);
		var o2=get(ioPool2, inst2);
		
		if(getNullability()==IONullability.Mode.DEFAULT_IF_NULL&&(o1==null||o2==null)){
			if(o1==null&&o2==null) return true;
			var acc=getAccessor();
			
			if(UtilL.instanceOf(acc.getType(), IOInstance.class)){
				var typ=Struct.ofUnknown(acc.getType());
				
				if(o1==null) o1=(ValueType)typ.requireEmptyConstructor().get();
				if(o2==null) o2=(ValueType)typ.requireEmptyConstructor().get();
			}else{
				throw new NotImplementedException(acc.getType()+"");//TODO implement equals of numbers?
			}
		}
		
		if(getAccessor().getType().isArray()){
			if(o1==o2) return true;
			if(o1==null||o2==null) return false;
			int l1=Array.getLength(o1);
			int l2=Array.getLength(o2);
			if(l1!=l2) return false;
			return switch(o1){
				case byte[] arr -> Arrays.equals(arr, (byte[])o2);
				case short[] arr -> Arrays.equals(arr, (short[])o2);
				case int[] arr -> Arrays.equals(arr, (int[])o2);
				case long[] arr -> Arrays.equals(arr, (long[])o2);
				case float[] arr -> Arrays.equals(arr, (float[])o2);
				case double[] arr -> Arrays.equals(arr, (double[])o2);
				case char[] arr -> Arrays.equals(arr, (char[])o2);
				case boolean[] arr -> Arrays.equals(arr, (boolean[])o2);
				case Object[] arr -> Arrays.equals(arr, (Object[])o2);
				default -> throw new NotImplementedException(o1.getClass().getName());
			};
		}
		
		return Objects.equals(o1, o2);
	}
	
	public int instanceHashCode(Struct.Pool<T> ioPool, T instance){
		return Objects.hashCode(get(ioPool, instance));
	}
	
	public void init(){
		getAccessor().init(this);
	}
	
	
	public String getName()              {return getAccessor().getName();}
	public FieldAccessor<T> getAccessor(){return accessor;}
	public Struct<T> declaringStruct(){
		var acc=getAccessor();
		return acc==null?null:acc.getDeclaringStruct();
	}
	
	private void requireLateData(){
		if(!lateDataInitialized){
			throw new IllegalStateException(this.getName()+" late data not initialized");
		}
	}
	public boolean lateDataInitialized(){
		return lateDataInitialized;
	}
	
	public FieldSet<T> getDependencies(){
		requireLateData();
		return Objects.requireNonNull(dependencies);
	}
	
	public boolean hasUsageHint(UsageHintType hint){
		var hints=getUsageHints();
		if(hints==null) return false;
		return hints.contains(hint);
	}
	public Stream<UsageHintType> usageHintsStream(){
		var hints=getUsageHints();
		if(hints==null) return Stream.of();
		return hints.stream();
	}
	@Nullable
	public EnumSet<UsageHintType> getUsageHints(){
		requireLateData();
		return usageHints;
	}
	
	public String toShortString(){
		return Objects.requireNonNull(getName());
	}
	@Override
	public String toString(){
		var struct=getAccessor().getDeclaringStruct();
		return (struct==null?"":struct.getType().getSimpleName())+"#"+toShortString();
	}
	
	
	/**
	 * @return a stream of fields that are directly referenced by the struct. (field that represents a group of fields should return the containing fields)
	 */
	public Stream<? extends IOField<T, ?>> streamUnpackedFields(){
		return Stream.of(this);
	}
	
	public IOField<T, ValueType> forceMaxAsFixedSize(){
		if(getSizeDescriptor().hasFixed()) return this;
		if(!getSizeDescriptor().hasMax()){
			try{
				throwInformativeFixedSizeError();
			}catch(Throwable e){
				throw new FixedFormatNotSupportedException(this, e);
			}
			throw new FixedFormatNotSupportedException(this);
		}
		var f=implMaxAsFixedSize();
		f.initLateData(getDependencies(), usageHintsStream());
		f.init();
		if(!f.getSizeDescriptor().hasFixed()) throw new RuntimeException(this+" failed to make itslef fixed");
		return f;
	}
	
	protected void throwInformativeFixedSizeError(){}
	
	protected IOField<T, ValueType> implMaxAsFixedSize(){
		throw new NotImplementedException();
	}
	
	public IONullability.Mode getNullability(){
		if(nullability==null){
			nullability=accessor==null?IONullability.Mode.NULLABLE:IOFieldTools.getNullability(accessor);
		}
		return nullability;
	}
	public boolean nullable(){
		return getNullability()==IONullability.Mode.NULLABLE;
	}
	
	protected final ValueType requireValNN(ValueType value){
		return FieldIsNullException.requireNonNull(this, value);
	}
	
	@Override
	public boolean equals(Object o){
		if(this==o) return true;
		if(!(o instanceof IOField<?, ?> ioField)) return false;
		
		var acc=getAccessor();
		if(acc==null){
			if(ioField.getAccessor()!=null) return false;
			return getName().equals(ioField.getName());
		}
		return acc.equals(ioField.getAccessor());
	}
	@Override
	public int hashCode(){
		return getName().hashCode();
	}
}
