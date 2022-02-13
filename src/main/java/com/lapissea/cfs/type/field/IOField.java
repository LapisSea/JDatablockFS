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
import com.lapissea.cfs.type.field.fields.reflection.IOFieldPrimitive;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.Nullable;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;

import java.io.IOException;
import java.util.*;
import java.util.stream.Stream;

import static com.lapissea.cfs.GlobalConfig.DEBUG_VALIDATION;

public abstract class IOField<T extends IOInstance<T>, ValueType>{
	
	static{
		TextUtil.SHORT_TO_STRINGS.register(OptionalLong.class, l->l.isEmpty()?"()L":"("+l.getAsLong()+")L");
	}
	
	
	public enum UsageHintType{
		SIZE_DATA
	}
	
	public record UsageHint(UsageHintType type, String target){}
	
	
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
			try(var reader=new BitInputStream(src)){
				readBits(ioPool, reader, instance);
				if(DEBUG_VALIDATION){
					reader.requireRead(getSizeDescriptor().calcUnknown(ioPool, provider, instance, WordSpace.BIT));
				}
			}
		}
		
		@Deprecated
		@Override
		public final void skipRead(Struct.Pool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
			try(var reader=new BitInputStream(src)){
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
	
	private boolean                initialized;
	private FieldSet<T>            dependencies;
	private EnumSet<UsageHintType> usageHints;
	
	private final IONullability.Mode nullability;
	
	public IOField(FieldAccessor<T> accessor){
		this.accessor=accessor;
		nullability=accessor==null?IONullability.Mode.NULLABLE:IOFieldTools.getNullability(accessor);
	}
	
	public void initLateData(FieldSet<T> deps, Stream<UsageHintType> hints){
		Utils.requireNull(dependencies);
		Utils.requireNull(usageHints);
		dependencies=deps;
		var h=EnumSet.noneOf(UsageHintType.class);
		hints.forEach(h::add);
		usageHints=Utils.nullIfEmpty(h);
		initialized=true;
	}
	
	public boolean isNull(Struct.Pool<T> ioPool, T instance){
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
	
	@SuppressWarnings("unchecked")
	public ValueType get(Struct.Pool<T> ioPool, T instance){
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
			return "{mod "+TextUtil.toShortString(field)+"}";
		}
	}
	
	public List<ValueGeneratorInfo<T, ?>> getGenerators(){
		return null;
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
	public String instanceToString(Struct.Pool<T> ioPool, T instance, boolean doShort){
		var val=get(ioPool, instance);
		if(val==null) return null;
		
		if(doShort){
			if(val instanceof IOInstance inst){
				return inst.toShortString();
			}
			return TextUtil.toShortString(val);
		}
		return TextUtil.toString(val);
	}
	
	@SuppressWarnings("unchecked")
	public boolean instancesEqual(Struct.Pool<T> ioPool1, T inst1, Struct.Pool<T> ioPool2, T inst2){
		var o1=get(ioPool1, inst1);
		var o2=get(ioPool2, inst2);
		
		if(nullability==IONullability.Mode.DEFAULT_IF_NULL&&(o1==null||o2==null)){
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
			return Arrays.equals((Object[])o1, (Object[])o2);
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
	public Struct<T> declaringStruct()   {return getAccessor().getDeclaringStruct();}
	public FieldAccessor<T> getAccessor(){return accessor;}
	public FieldSet<T> getDependencies(){
		if(!initialized) throw new IllegalStateException();
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
		if(!initialized) throw new IllegalStateException();
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
