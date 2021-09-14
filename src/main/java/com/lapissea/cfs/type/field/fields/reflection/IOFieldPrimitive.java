package com.lapissea.cfs.type.field.fields.reflection;

import com.lapissea.cfs.chunk.ChunkDataProvider;
import com.lapissea.cfs.io.bit.BitReader;
import com.lapissea.cfs.io.bit.BitWriter;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.access.IFieldAccessor;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import static com.lapissea.cfs.objects.NumberSize.BYTE;
import static com.lapissea.cfs.objects.NumberSize.*;
import static com.lapissea.cfs.type.WordSpace.*;

public abstract class IOFieldPrimitive<T extends IOInstance<T>, ValueType> extends IOField<T, ValueType>{
	
	public static boolean isPrimitive(Type clazz){
		return getFieldMaker(clazz)!=null;
	}
	
	public static <T extends IOInstance<T>> IOField<T, ?> make(IFieldAccessor<T> field){
		return Objects.requireNonNull(IOFieldPrimitive.<T>getFieldMaker(field.getType())).apply(field);
	}
	
	public static <T extends IOInstance<T>> Function<IFieldAccessor<T>, IOField<T, ?>> getFieldMaker(Type clazz){
		if(clazz==double.class||clazz==Double.class) return FDouble::new;
		if(clazz==float.class||clazz==Float.class) return FFloat::new;
		if(clazz==long.class||clazz==Long.class) return FLong::new;
		if(clazz==int.class||clazz==Integer.class) return FInt::new;
		if(clazz==byte.class||clazz==Byte.class) return FByte::new;
		if(clazz==boolean.class||clazz==Boolean.class) return FBoolean::new;
		
		return null;
	}
	
	public static class FDouble<T extends IOInstance<T>> extends IOFieldPrimitive<T, Double>{
		
		
		public FDouble(IFieldAccessor<T> field){
			this(field, false);
		}
		public FDouble(IFieldAccessor<T> field, boolean forceFixed){
			super(field, forceFixed, LONG);
		}
		@Override
		protected EnumSet<NumberSize> allowedSizes(){
			return EnumSet.of(VOID, SHORT, INT, LONG);
		}
		
		private double getValue(T instance){
			return getAccessor().getDouble(instance);
		}
		
		public void setValue(T instance, double value){
			getAccessor().setDouble(instance, value);
		}
		
		@Deprecated
		@Override
		public Double get(T instance){
			return getValue(instance);
		}
		@Deprecated
		@Override
		public void set(T instance, Double value){
			setValue(instance, value);
		}
		
		@Override
		public List<IOField<T, ?>> write(ChunkDataProvider provider, ContentWriter dest, T instance) throws IOException{
			var size=getSize(instance);
			size.writeFloating(dest, getValue(instance));
			return List.of();
		}
		
		@Override
		public void read(ChunkDataProvider provider, ContentReader src, T instance) throws IOException{
			var size=getSize(instance);
			setValue(instance, size.readFloating(src));
		}
		
		@Override
		public boolean instancesEqual(T inst1, T inst2){
			return getValue(inst1)==getValue(inst2);
		}
		@Override
		public int instanceHashCode(T instance){
			return Double.hashCode(getValue(instance));
		}
	}
	
	public static class FFloat<T extends IOInstance<T>> extends IOFieldPrimitive<T, Float>{
		
		
		public FFloat(IFieldAccessor<T> field){
			this(field, false);
		}
		public FFloat(IFieldAccessor<T> field, boolean forceFixed){
			super(field, forceFixed, INT);
		}
		@Override
		protected EnumSet<NumberSize> allowedSizes(){
			return EnumSet.of(VOID, SHORT, INT);
		}
		
		private float getValue(T instance){
			return getAccessor().getFloat(instance);
		}
		
		public void setValue(T instance, float value){
			getAccessor().setFloat(instance, value);
		}
		
		@Deprecated
		@Override
		public Float get(T instance){
			return getValue(instance);
		}
		@Deprecated
		@Override
		public void set(T instance, Float value){
			setValue(instance, value);
		}
		
		@Override
		public List<IOField<T, ?>> write(ChunkDataProvider provider, ContentWriter dest, T instance) throws IOException{
			var size=getSize(instance);
			size.writeFloating(dest, getValue(instance));
			return List.of();
		}
		
		@Override
		public void read(ChunkDataProvider provider, ContentReader src, T instance) throws IOException{
			var size=getSize(instance);
			setValue(instance, (float)size.readFloating(src));
		}
		
		@Override
		public boolean instancesEqual(T inst1, T inst2){
			return getValue(inst1)==getValue(inst2);
		}
		@Override
		public int instanceHashCode(T instance){
			return Float.hashCode(getValue(instance));
		}
	}
	
	public static class FLong<T extends IOInstance<T>> extends IOFieldPrimitive<T, Long>{
		
		public FLong(IFieldAccessor<T> field){
			this(field, false);
		}
		public FLong(IFieldAccessor<T> field, boolean forceFixed){
			super(field, forceFixed, LONG);
		}
		
		private long getValue(T instance){
			return getAccessor().getLong(instance);
		}
		
		public void setValue(T instance, long value){
			getAccessor().setLong(instance, value);
		}
		
		@Deprecated
		@Override
		public Long get(T instance){
			return getValue(instance);
		}
		@Deprecated
		@Override
		public void set(T instance, Long value){
			setValue(instance, value);
		}
		
		@Override
		public List<IOField<T, ?>> write(ChunkDataProvider provider, ContentWriter dest, T instance) throws IOException{
			var size=getSize(instance);
			size.write(dest, getValue(instance));
			return List.of();
		}
		
		@Override
		public void read(ChunkDataProvider provider, ContentReader src, T instance) throws IOException{
			var size=getSize(instance);
			setValue(instance, size.read(src));
		}
		
		@Override
		public boolean instancesEqual(T inst1, T inst2){
			return getValue(inst1)==getValue(inst2);
		}
		@Override
		public int instanceHashCode(T instance){
			return Long.hashCode(getValue(instance));
		}
	}
	
	public static class FInt<T extends IOInstance<T>> extends IOFieldPrimitive<T, Integer>{
		
		
		public FInt(IFieldAccessor<T> field){
			this(field, false);
		}
		public FInt(IFieldAccessor<T> field, boolean forceFixed){
			super(field, forceFixed, INT);
		}
		@Override
		protected EnumSet<NumberSize> allowedSizes(){
			var all=EnumSet.allOf(NumberSize.class);
			all.removeIf(s->s.greaterThan(INT));
			return all;
		}
		
		private int getValue(T instance){
			return getAccessor().getInt(instance);
		}
		
		public void setValue(T instance, int value){
			getAccessor().setInt(instance, value);
		}
		
		@Deprecated
		@Override
		public Integer get(T instance){
			return getValue(instance);
		}
		@Deprecated
		@Override
		public void set(T instance, Integer value){
			setValue(instance, value);
		}
		
		@Override
		public List<IOField<T, ?>> write(ChunkDataProvider provider, ContentWriter dest, T instance) throws IOException{
			var size=getSize(instance);
			size.write(dest, getValue(instance));
			return List.of();
		}
		
		@Override
		public void read(ChunkDataProvider provider, ContentReader src, T instance) throws IOException{
			var size=getSize(instance);
			setValue(instance, (int)size.read(src));
		}
		
		@Override
		public boolean instancesEqual(T inst1, T inst2){
			return getValue(inst1)==getValue(inst2);
		}
		@Override
		public int instanceHashCode(T instance){
			return Integer.hashCode(getValue(instance));
		}
	}
	
	public static class FByte<T extends IOInstance<T>> extends IOFieldPrimitive<T, Byte>{
		
		public FByte(IFieldAccessor<T> field){
			this(field, false);
		}
		
		public FByte(IFieldAccessor<T> field, boolean forceFixed){
			super(field, forceFixed, BYTE);
		}
		@Override
		protected EnumSet<NumberSize> allowedSizes(){
			var all=EnumSet.allOf(NumberSize.class);
			all.removeIf(s->s.greaterThan(BYTE));
			return all;
		}
		
		private byte getValue(T instance){
			return getAccessor().getByte(instance);
		}
		
		public void setValue(T instance, byte value){
			getAccessor().setByte(instance, value);
		}
		
		@Deprecated
		@Override
		public Byte get(T instance){
			return getValue(instance);
		}
		@Deprecated
		@Override
		public void set(T instance, Byte value){
			setValue(instance, value);
		}
		
		@Override
		public List<IOField<T, ?>> write(ChunkDataProvider provider, ContentWriter dest, T instance) throws IOException{
			dest.writeInt1(getValue(instance));
			return List.of();
		}
		
		@Override
		public void read(ChunkDataProvider provider, ContentReader src, T instance) throws IOException{
			setValue(instance, src.readInt1());
		}
		
		@Override
		public boolean instancesEqual(T inst1, T inst2){
			return getValue(inst1)==getValue(inst2);
		}
		@Override
		public int instanceHashCode(T instance){
			return Byte.hashCode(getValue(instance));
		}
	}
	
	public static class FBoolean<T extends IOInstance<T>> extends IOField.Bit<T, Boolean>{
		
		protected FBoolean(IFieldAccessor<T> field){
			super(field);
		}
		
		private boolean getValue(T instance){
			return getAccessor().getBoolean(instance);
		}
		
		public void setValue(T instance, boolean value){
			getAccessor().setBoolean(instance, value);
		}
		
		@Deprecated
		@Override
		public Boolean get(T instance){
			return getValue(instance);
		}
		@Deprecated
		@Override
		public void set(T instance, Boolean value){
			setValue(instance, value);
		}
		
		@Override
		public void writeBits(BitWriter<?> dest, T instance) throws IOException{
			dest.writeBoolBit(getValue(instance));
		}
		@Override
		public void readBits(BitReader src, T instance) throws IOException{
			setValue(instance, src.readBoolBit());
		}
		
		@Override
		public boolean instancesEqual(T inst1, T inst2){
			return getValue(inst1)==getValue(inst2);
		}
		@Override
		public int instanceHashCode(T instance){
			return Boolean.hashCode(getValue(instance));
		}
		
		@Override
		public SizeDescriptor<T> getSizeDescriptor(){
			return SizeDescriptor.Fixed.of(BIT, 1);
		}
	}
	
	private final boolean                 forceFixed;
	private final NumberSize              size;
	private       Function<T, NumberSize> dynamicSize;
	private       SizeDescriptor<T>       sizeDescriptor;
	
	protected IOFieldPrimitive(IFieldAccessor<T> field, boolean forceFixed, NumberSize size){
		super(field);
		this.forceFixed=forceFixed;
		this.size=size;
	}
	
	@Override
	public void init(){
		super.init();
		var field  =forceFixed?null:IOFieldTools.getDynamicSize(getAccessor());
		var allowed=allowedSizes();
		if(field!=null){
			dynamicSize=instance->{
				var val=field.get(instance);
				if(!allowed.contains(val)) throw new IllegalStateException(val+" is not an allowed size in "+allowed);
				return val;
			};
			sizeDescriptor=new SizeDescriptor.Unknown<>(
				allowed.stream().mapToLong(NumberSize::bytes).min().orElse(0),
				allowed.stream().mapToLong(NumberSize::bytes).max()){
				@Override
				public long calcUnknown(T instance){
					return getSize(instance).bytes;
				}
			};
		}else{
			sizeDescriptor=new SizeDescriptor.Fixed<>(size.bytes);
		}
	}
	
	protected EnumSet<NumberSize> allowedSizes(){
		return EnumSet.allOf(NumberSize.class);
	}
	
	protected NumberSize getSize(T instance){
		if(dynamicSize!=null) return dynamicSize.apply(instance);
		return size;
	}
	protected Function<T, NumberSize> getDynamicSize(){
		return dynamicSize;
	}
	
	@Override
	public SizeDescriptor<T> getSizeDescriptor(){
		return sizeDescriptor;
	}
	@Override
	public IOField<T, ValueType> implMaxAsFixedSize(){
		try{
			return (IOField<T, ValueType>)getClass().getConstructor(IFieldAccessor.class, boolean.class).newInstance(getAccessor(), true);
		}catch(ReflectiveOperationException e){
			throw new RuntimeException(e);
		}
	}
}
