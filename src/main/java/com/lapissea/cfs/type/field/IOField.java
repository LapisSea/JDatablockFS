package com.lapissea.cfs.type.field;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.ChunkDataProvider;
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
import com.lapissea.cfs.type.FieldSet;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.field.access.IFieldAccessor;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.Nullable;
import com.lapissea.util.TextUtil;

import java.io.IOException;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.stream.Stream;

import static com.lapissea.cfs.GlobalConfig.*;

public abstract class IOField<T extends IOInstance<T>, ValueType>{
	
	static{
		TextUtil.SHORT_TO_STRINGS.register(OptionalLong.class, l->l.isEmpty()?"()L":"("+l.getAsLong()+")L");
	}
	
	
	public enum UsageHintType{
		SIZE_DATA
	}
	
	public static record UsageHint(UsageHintType type, String target){}
	
	
	public abstract static class Ref<T extends IOInstance<T>, Type extends IOInstance<Type>> extends IOField<T, Type>{
		
		public Ref(IFieldAccessor<T> accessor){
			super(accessor);
		}
		
		public void allocateUnmanaged(T instance) throws IOException{
			IOInstance.Unmanaged<?> unmanaged=(IOInstance.Unmanaged<?>)instance;
			allocate(instance, unmanaged.getChunkProvider(), unmanaged.getGenerics());
		}
		
		public abstract void allocate(T instance, ChunkDataProvider provider, GenericContext genericContext) throws IOException;
		public abstract Reference getReference(T instance);
		public abstract StructPipe<Type> getReferencedPipe(T instance);
		
		@Override
		public IOField.Ref<T, Type> implMaxAsFixedSize(){
			throw new NotImplementedException();
		}
	}
	
	public abstract static class Bit<T extends IOInstance<T>, Type> extends IOField<T, Type>{
		
		protected Bit(IFieldAccessor<T> field){
			super(field);
		}
		
		@Deprecated
		@Override
		public final List<IOField<T, ?>> write(ChunkDataProvider provider, ContentWriter dest, T instance) throws IOException{
			try(var writer=new BitOutputStream(dest)){
				writeBits(writer, instance);
				if(DEBUG_VALIDATION){
					writer.requireWritten(getSizeDescriptor().calcUnknown(instance));
				}
			}
			return List.of();
		}
		
		@Deprecated
		@Override
		public final void read(ChunkDataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
			try(var reader=new BitInputStream(src)){
				readBits(reader, instance);
				if(DEBUG_VALIDATION){
					reader.requireRead(getSizeDescriptor().calcUnknown(instance));
				}
			}
		}
		
		@Deprecated
		@Override
		public final void skipRead(ChunkDataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
			try(var reader=new BitInputStream(src)){
				skipReadBits(reader, instance);
				if(DEBUG_VALIDATION){
					reader.requireRead(getSizeDescriptor().calcUnknown(instance));
				}
			}
		}
		
		public abstract void writeBits(BitWriter<?> dest, T instance) throws IOException;
		public abstract void readBits(BitReader src, T instance) throws IOException;
		public abstract void skipReadBits(BitReader src, T instance) throws IOException;
		
		@Override
		public IOField.Bit<T, Type> implMaxAsFixedSize(){
			throw new NotImplementedException();
		}
	}
	
	private final IFieldAccessor<T> accessor;
	
	private       FieldSet<T, ?>         dependencies;
	private       EnumSet<UsageHintType> usageHints;
	private final IONullability.Mode     nullability;
	
	public IOField(IFieldAccessor<T> accessor){
		this.accessor=accessor;
		nullability=accessor==null?IONullability.Mode.NULLABLE:IOFieldTools.getNullability(accessor);
	}
	
	public void initLateData(FieldSet<T, ?> deps, Stream<UsageHintType> hints){
		Utils.requireNull(dependencies);
		Utils.requireNull(usageHints);
		dependencies=deps;
		var h=EnumSet.noneOf(UsageHintType.class);
		hints.forEach(h::add);
		usageHints=h;
	}
	
	public boolean isNull(T instance){
		try{
			var val=get(instance);
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
	public ValueType get(T instance){
		return (ValueType)getAccessor().get(instance);
	}
	
	public void set(T instance, ValueType value){
		getAccessor().set(instance, value);
	}
	
	public abstract SizeDescriptor<T> getSizeDescriptor();
	
	/**
	 * @return a list of fields that have to be written after this function has executed. If no fields are required, return {@link List#of()} or null
	 */
	@Nullable
	public abstract List<IOField<T, ?>> write(ChunkDataProvider provider, ContentWriter dest, T instance) throws IOException;
	@Nullable
	public final List<IOField<T, ?>> writeReported(ChunkDataProvider provider, ContentWriter dest, T instance) throws IOException{
		try{
			return write(provider, dest, instance);
		}catch(Exception e){
			throw reportWriteFail(this, e);
		}
	}
	
	protected IOException reportWriteFail(IOField<T, ?> fi, Exception e) throws IOException{
		throw new IOException("Failed to write "+TextUtil.toShortString(fi), e);
	}
	
	public abstract void read(ChunkDataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException;
	public final void readReported(ChunkDataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		try{
			read(provider, src, instance, genericContext);
		}catch(Exception e){
			throw reportReadFail(this, e);
		}
	}
	protected IOException reportReadFail(IOField<T, ?> fi, Exception e) throws IOException{
		throw new IOException("Failed to read "+TextUtil.toShortString(fi), e);
	}
	
	public abstract void skipRead(ChunkDataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException;
	public final void skipReadReported(ChunkDataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		try{
			skipRead(provider, src, instance, genericContext);
		}catch(Exception e){
			throw reportSkipReadFail(this, e);
		}
	}
	protected IOException reportSkipReadFail(IOField<T, ?> fi, Exception e) throws IOException{
		throw new IOException("Failed to skip read "+TextUtil.toShortString(fi), e);
	}
	
	
	/**
	 * @return string of the resolved value or null if string has no substance
	 */
	public String instanceToString(T instance, boolean doShort){
		var val=get(instance);
		if(val==null) return null;
		return doShort?TextUtil.toShortString(val):TextUtil.toString(val);
	}
	
	public boolean instancesEqual(T inst1, T inst2){
		return Objects.equals(get(inst1), get(inst2));
	}
	
	public int instanceHashCode(T instance){
		return Objects.hashCode(get(instance));
	}
	
	public void init(){
		getAccessor().init(this);
	}
	
	
	public String getName()                      {return getAccessor().getName();}
	public Struct<T> declaringStruct()           {return getAccessor().getDeclaringStruct();}
	public IFieldAccessor<T> getAccessor()       {return accessor;}
	public FieldSet<T, ?> getDependencies()      {return Objects.requireNonNull(dependencies);}
	public EnumSet<UsageHintType> getUsageHints(){return Objects.requireNonNull(usageHints);}
	
	public String toShortString(){
		return Objects.requireNonNull(getName());
	}
	@Override
	public String toString(){
		return getAccessor().getDeclaringStruct().getType().getSimpleName()+"#"+toShortString();
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
		f.initLateData(getDependencies(), getUsageHints().stream());
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
		
		return getAccessor()!=null?getAccessor().equals(ioField.getAccessor()):ioField.getAccessor()==null;
	}
	@Override
	public int hashCode(){
		return getAccessor()!=null?getAccessor().hashCode():0;
	}
}
