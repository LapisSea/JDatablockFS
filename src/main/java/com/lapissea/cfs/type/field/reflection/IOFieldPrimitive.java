package com.lapissea.cfs.type.field.reflection;

import com.lapissea.cfs.io.bit.BitReader;
import com.lapissea.cfs.io.bit.BitWriter;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.type.IOField;
import com.lapissea.cfs.type.IOInstance;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.function.Function;

public abstract class IOFieldPrimitive{
	
	public static boolean isPrimitive(Class<?> clazz){
		return getFieldMaker(clazz)!=null;
	}
	
	@SuppressWarnings("unchecked")
	public static <T extends IOInstance<T>> IOField<T, ?> make(Field field){
		return (IOField<T, ?>)Objects.requireNonNull(getFieldMaker(field.getType())).apply(field);
	}
	
	public static <T extends IOInstance<T>> Function<Field, IOField<T, ?>> getFieldMaker(Class<?> clazz){
		if(clazz==double.class||clazz==Double.class) return FDouble::new;
		if(clazz==float.class||clazz==Float.class) return FFloat::new;
		if(clazz==long.class||clazz==Long.class) return FLong::new;
		if(clazz==int.class||clazz==Integer.class) return FInt::new;
		if(clazz==byte.class||clazz==Byte.class) return FByte::new;
		if(clazz==boolean.class||clazz==Boolean.class) return FBoolean::new;
		
		return null;
	}
	
	public static class FDouble<T extends IOInstance<T>> extends IOField<T, Double>{
		
		
		private final NumberSize size=NumberSize.LONG;
		private final Field      field;
		
		public FDouble(Field field){
			this.field=field;
		}
		
		private double getValue(T instance){
			try{
				return field.getDouble(instance);
			}catch(ReflectiveOperationException e){
				throw new RuntimeException(e);
			}
		}
		
		public void setValue(T instance, double value){
			try{
				field.setDouble(instance, value);
			}catch(ReflectiveOperationException e){
				throw new RuntimeException(e);
			}
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
		public long calcSize(T instance){
			return size.bytes;
		}
		@Override
		public OptionalLong getFixedSize(){
			return OptionalLong.of(size.bytes);
		}
		
		@Override
		public void write(ContentWriter dest, T instance) throws IOException{
			size.writeFloating(dest, getValue(instance));
		}
		
		@Override
		public void read(ContentReader src, T instance) throws IOException{
			setValue(instance, size.readFloating(src));
		}
		
		@Override
		public String getName(){
			return field.getName();
		}
	}
	
	public static class FFloat<T extends IOInstance<T>> extends IOField<T, Float>{
		
		
		private final NumberSize size=NumberSize.INT;
		private final Field      field;
		
		public FFloat(Field field){
			this.field=field;
		}
		
		private float getValue(T instance){
			try{
				return field.getFloat(instance);
			}catch(ReflectiveOperationException e){
				throw new RuntimeException(e);
			}
		}
		
		public void setValue(T instance, float value){
			try{
				field.setFloat(instance, value);
			}catch(ReflectiveOperationException e){
				throw new RuntimeException(e);
			}
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
		public long calcSize(T instance){
			return size.bytes;
		}
		@Override
		public OptionalLong getFixedSize(){
			return OptionalLong.of(size.bytes);
		}
		
		@Override
		public void write(ContentWriter dest, T instance) throws IOException{
			size.writeFloating(dest, getValue(instance));
		}
		
		@Override
		public void read(ContentReader src, T instance) throws IOException{
			setValue(instance, (float)size.readFloating(src));
		}
		
		@Override
		public String getName(){
			return field.getName();
		}
	}
	
	public static class FLong<T extends IOInstance<T>> extends IOField<T, Long>{
		
		
		private final NumberSize size=NumberSize.LONG;
		private final Field      field;
		
		public FLong(Field field){
			this.field=field;
		}
		
		private long getValue(T instance){
			try{
				return field.getLong(instance);
			}catch(ReflectiveOperationException e){
				throw new RuntimeException(e);
			}
		}
		
		public void setValue(T instance, long value){
			try{
				field.setLong(instance, value);
			}catch(ReflectiveOperationException e){
				throw new RuntimeException(e);
			}
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
		public long calcSize(T instance){
			return size.bytes;
		}
		@Override
		public OptionalLong getFixedSize(){
			return OptionalLong.of(size.bytes);
		}
		
		@Override
		public void write(ContentWriter dest, T instance) throws IOException{
			size.write(dest, getValue(instance));
		}
		
		@Override
		public void read(ContentReader src, T instance) throws IOException{
			setValue(instance, size.read(src));
		}
		
		@Override
		public String getName(){
			return field.getName();
		}
	}
	
	public static class FInt<T extends IOInstance<T>> extends IOField<T, Integer>{
		
		
		private final NumberSize size=NumberSize.INT;
		private final Field      field;
		
		public FInt(Field field){
			this.field=field;
		}
		
		private int getValue(T instance){
			try{
				return field.getInt(instance);
			}catch(ReflectiveOperationException e){
				throw new RuntimeException(e);
			}
		}
		
		public void setValue(T instance, int value){
			try{
				field.setInt(instance, value);
			}catch(ReflectiveOperationException e){
				throw new RuntimeException(e);
			}
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
		public long calcSize(T instance){
			return size.bytes;
		}
		@Override
		public OptionalLong getFixedSize(){
			return OptionalLong.of(size.bytes);
		}
		
		@Override
		public void write(ContentWriter dest, T instance) throws IOException{
			size.write(dest, getValue(instance));
		}
		
		@Override
		public void read(ContentReader src, T instance) throws IOException{
			setValue(instance, (int)size.read(src));
		}
		
		@Override
		public String getName(){
			return field.getName();
		}
	}
	
	public static class FByte<T extends IOInstance<T>> extends IOField<T, Byte>{
		
		private final Field field;
		
		public FByte(Field field){
			this.field=field;
		}
		
		private byte getValue(T instance){
			try{
				return field.getByte(instance);
			}catch(ReflectiveOperationException e){
				throw new RuntimeException(e);
			}
		}
		
		public void setValue(T instance, byte value){
			try{
				field.setByte(instance, value);
			}catch(ReflectiveOperationException e){
				throw new RuntimeException(e);
			}
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
		public long calcSize(T instance){
			return 1;
		}
		@Override
		public OptionalLong getFixedSize(){
			return OptionalLong.of(1);
		}
		
		@Override
		public void write(ContentWriter dest, T instance) throws IOException{
			dest.writeInt1(getValue(instance));
		}
		
		@Override
		public void read(ContentReader src, T instance) throws IOException{
			setValue(instance, src.readInt1());
		}
		
		@Override
		public String getName(){
			return field.getName();
		}
	}
	
	public static class FBoolean<T extends IOInstance<T>> extends IOField.Bit<T, Boolean>{
		
		
		private final Field field;
		
		public FBoolean(Field field){
			this.field=field;
		}
		
		private boolean getValue(T instance){
			try{
				return field.getBoolean(instance);
			}catch(ReflectiveOperationException e){
				throw new RuntimeException(e);
			}
		}
		
		public void setValue(T instance, boolean value){
			try{
				field.setBoolean(instance, value);
			}catch(ReflectiveOperationException e){
				throw new RuntimeException(e);
			}
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
		public long calcSize(T instance){
			return 1;
		}
		@Override
		public OptionalLong getFixedSize(){
			return OptionalLong.of(1);
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
		public String getName(){
			return field.getName();
		}
	}
}
