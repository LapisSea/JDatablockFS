package com.lapissea.cfs.type.field.fields.reflection;

import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.io.bit.BitReader;
import com.lapissea.cfs.io.bit.BitWriter;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.SupportedPrimitive;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.access.FieldAccessor;

import java.io.IOException;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Optional;
import java.util.function.BiFunction;

import static com.lapissea.cfs.objects.NumberSize.*;
import static com.lapissea.cfs.type.WordSpace.BIT;

public abstract class IOFieldPrimitive<T extends IOInstance<T>, ValueType> extends IOField<T, ValueType>{
	
	public static <T extends IOInstance<T>> IOField<T, ?> make(FieldAccessor<T> field){
		return SupportedPrimitive.get(field.getType()).map(t->switch(t){
			case DOUBLE -> new FDouble<>(field);
			case CHAR -> new FChar<>(field);
			case FLOAT -> new FFloat<>(field);
			case LONG -> new FLong<>(field);
			case INT -> new FInt<>(field);
			case SHORT -> new FShort<>(field);
			case BYTE -> new FByte<>(field);
			case BOOLEAN -> new FBoolean<>(field);
		}).orElseThrow();
	}
	
	public static class FDouble<T extends IOInstance<T>> extends IOFieldPrimitive<T, Double>{
		
		
		public FDouble(FieldAccessor<T> field){
			this(field, false);
		}
		public FDouble(FieldAccessor<T> field, boolean forceFixed){
			super(field, forceFixed, LONG);
		}
		@Override
		protected EnumSet<NumberSize> allowedSizes(){
			return EnumSet.of(VOID, SHORT, INT, LONG);
		}
		
		private double getValue(Struct.Pool<T> ioPool, T instance){
			return getAccessor().getDouble(ioPool, instance);
		}
		
		public void setValue(Struct.Pool<T> ioPool, T instance, double value){
			getAccessor().setDouble(ioPool, instance, value);
		}
		
		@Deprecated
		@Override
		public Double get(Struct.Pool<T> ioPool, T instance){
			return getValue(ioPool, instance);
		}
		@Deprecated
		@Override
		public void set(Struct.Pool<T> ioPool, T instance, Double value){
			setValue(ioPool, instance, value);
		}
		
		@Override
		public void write(Struct.Pool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
			var size=getSize(ioPool, instance);
			size.writeFloating(dest, getValue(ioPool, instance));
		}
		
		@Override
		public void read(Struct.Pool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
			var size=getSize(ioPool, instance);
			setValue(ioPool, instance, size.readFloating(src));
		}
		
		@Override
		public Optional<String> instanceToString(Struct.Pool<T> ioPool, T instance, boolean doShort, String start, String end, String fieldValueSeparator, String fieldSeparator){
			return Optional.of(String.valueOf(getValue(ioPool, instance)));
		}
		
		@Override
		public boolean instancesEqual(Struct.Pool<T> ioPool1, T inst1, Struct.Pool<T> ioPool2, T inst2){
			return getValue(ioPool1, inst1)==getValue(ioPool2, inst2);
		}
		@Override
		public int instanceHashCode(Struct.Pool<T> ioPool, T instance){
			return Double.hashCode(getValue(ioPool, instance));
		}
	}
	
	public static class FChar<T extends IOInstance<T>> extends IOFieldPrimitive<T, Character>{
		
		
		public FChar(FieldAccessor<T> field){
			this(field, false);
		}
		public FChar(FieldAccessor<T> field, boolean forceFixed){
			super(field, forceFixed, SHORT);
		}
		@Override
		protected EnumSet<NumberSize> allowedSizes(){
			return EnumSet.of(VOID, BYTE, SHORT);
		}
		
		private char getValue(Struct.Pool<T> ioPool, T instance){
			return getAccessor().getChar(ioPool, instance);
		}
		
		public void setValue(Struct.Pool<T> ioPool, T instance, char value){
			getAccessor().setChar(ioPool, instance, value);
		}
		
		@Deprecated
		@Override
		public Character get(Struct.Pool<T> ioPool, T instance){
			return getValue(ioPool, instance);
		}
		@Deprecated
		@Override
		public void set(Struct.Pool<T> ioPool, T instance, Character value){
			setValue(ioPool, instance, value);
		}
		
		@Override
		public void write(Struct.Pool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
			var size=getSize(ioPool, instance);
			size.writeFloating(dest, getValue(ioPool, instance));
		}
		
		@Override
		public void read(Struct.Pool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
			var size=getSize(ioPool, instance);
			setValue(ioPool, instance, (char)size.read(src));
		}
		
		@Override
		public Optional<String> instanceToString(Struct.Pool<T> ioPool, T instance, boolean doShort, String start, String end, String fieldValueSeparator, String fieldSeparator){
			return Optional.of(String.valueOf(getValue(ioPool, instance)));
		}
		
		@Override
		public boolean instancesEqual(Struct.Pool<T> ioPool1, T inst1, Struct.Pool<T> ioPool2, T inst2){
			return getValue(ioPool1, inst1)==getValue(ioPool2, inst2);
		}
		@Override
		public int instanceHashCode(Struct.Pool<T> ioPool, T instance){
			return Character.hashCode(getValue(ioPool, instance));
		}
	}
	
	public static class FFloat<T extends IOInstance<T>> extends IOFieldPrimitive<T, Float>{
		
		
		public FFloat(FieldAccessor<T> field){
			this(field, false);
		}
		public FFloat(FieldAccessor<T> field, boolean forceFixed){
			super(field, forceFixed, INT);
		}
		@Override
		protected EnumSet<NumberSize> allowedSizes(){
			return EnumSet.of(VOID, SHORT, INT);
		}
		
		private float getValue(Struct.Pool<T> ioPool, T instance){
			return getAccessor().getFloat(ioPool, instance);
		}
		
		public void setValue(Struct.Pool<T> ioPool, T instance, float value){
			getAccessor().setFloat(ioPool, instance, value);
		}
		
		@Deprecated
		@Override
		public Float get(Struct.Pool<T> ioPool, T instance){
			return getValue(ioPool, instance);
		}
		@Deprecated
		@Override
		public void set(Struct.Pool<T> ioPool, T instance, Float value){
			setValue(ioPool, instance, value);
		}
		
		@Override
		public void write(Struct.Pool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
			var size=getSize(ioPool, instance);
			size.writeFloating(dest, getValue(ioPool, instance));
		}
		
		@Override
		public void read(Struct.Pool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
			var size=getSize(ioPool, instance);
			setValue(ioPool, instance, (float)size.readFloating(src));
		}
		
		@Override
		public Optional<String> instanceToString(Struct.Pool<T> ioPool, T instance, boolean doShort, String start, String end, String fieldValueSeparator, String fieldSeparator){
			return Optional.of(String.valueOf(getValue(ioPool, instance)));
		}
		
		@Override
		public boolean instancesEqual(Struct.Pool<T> ioPool1, T inst1, Struct.Pool<T> ioPool2, T inst2){
			return getValue(ioPool1, inst1)==getValue(ioPool2, inst2);
		}
		@Override
		public int instanceHashCode(Struct.Pool<T> ioPool, T instance){
			return Float.hashCode(getValue(ioPool, instance));
		}
	}
	
	public static class FLong<T extends IOInstance<T>> extends IOFieldPrimitive<T, Long>{
		
		public FLong(FieldAccessor<T> field){
			this(field, false);
		}
		public FLong(FieldAccessor<T> field, boolean forceFixed){
			super(field, forceFixed, LONG);
		}
		
		public long getValue(Struct.Pool<T> ioPool, T instance){
			return getAccessor().getLong(ioPool, instance);
		}
		
		public void setValue(Struct.Pool<T> ioPool, T instance, long value){
			getAccessor().setLong(ioPool, instance, value);
		}
		
		@Deprecated
		@Override
		public Long get(Struct.Pool<T> ioPool, T instance){
			return getValue(ioPool, instance);
		}
		@Deprecated
		@Override
		public void set(Struct.Pool<T> ioPool, T instance, Long value){
			setValue(ioPool, instance, value);
		}
		
		@Override
		public void write(Struct.Pool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
			var size=getSize(ioPool, instance);
			size.write(dest, getValue(ioPool, instance));
		}
		
		@Override
		public void read(Struct.Pool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
			var size=getSize(ioPool, instance);
			setValue(ioPool, instance, size.read(src));
		}
		
		@Override
		public Optional<String> instanceToString(Struct.Pool<T> ioPool, T instance, boolean doShort, String start, String end, String fieldValueSeparator, String fieldSeparator){
			return Optional.of(String.valueOf(getValue(ioPool, instance)));
		}
		
		@Override
		public boolean instancesEqual(Struct.Pool<T> ioPool1, T inst1, Struct.Pool<T> ioPool2, T inst2){
			return getValue(ioPool1, inst1)==getValue(ioPool2, inst2);
		}
		@Override
		public int instanceHashCode(Struct.Pool<T> ioPool, T instance){
			return Long.hashCode(getValue(ioPool, instance));
		}
	}
	
	public static class FInt<T extends IOInstance<T>> extends IOFieldPrimitive<T, Integer>{
		
		
		public FInt(FieldAccessor<T> field){
			this(field, false);
		}
		public FInt(FieldAccessor<T> field, boolean forceFixed){
			super(field, forceFixed, INT);
		}
		@Override
		protected EnumSet<NumberSize> allowedSizes(){
			var all=EnumSet.allOf(NumberSize.class);
			all.removeIf(s->s.greaterThan(INT));
			return all;
		}
		
		public int getValue(Struct.Pool<T> ioPool, T instance){
			return getAccessor().getInt(ioPool, instance);
		}
		
		public void setValue(Struct.Pool<T> ioPool, T instance, int value){
			getAccessor().setInt(ioPool, instance, value);
		}
		
		@Deprecated
		@Override
		public Integer get(Struct.Pool<T> ioPool, T instance){
			return getValue(ioPool, instance);
		}
		@Deprecated
		@Override
		public void set(Struct.Pool<T> ioPool, T instance, Integer value){
			setValue(ioPool, instance, value);
		}
		
		@Override
		public void write(Struct.Pool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
			var size=getSize(ioPool, instance);
			size.write(dest, getValue(ioPool, instance));
		}
		
		@Override
		public void read(Struct.Pool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
			var size=getSize(ioPool, instance);
			setValue(ioPool, instance, (int)size.read(src));
		}
		
		@Override
		public Optional<String> instanceToString(Struct.Pool<T> ioPool, T instance, boolean doShort, String start, String end, String fieldValueSeparator, String fieldSeparator){
			return Optional.of(String.valueOf(getValue(ioPool, instance)));
		}
		
		@Override
		public boolean instancesEqual(Struct.Pool<T> ioPool1, T inst1, Struct.Pool<T> ioPool2, T inst2){
			return getValue(ioPool1, inst1)==getValue(ioPool2, inst2);
		}
		@Override
		public int instanceHashCode(Struct.Pool<T> ioPool, T instance){
			return Integer.hashCode(getValue(ioPool, instance));
		}
	}
	
	public static class FShort<T extends IOInstance<T>> extends IOFieldPrimitive<T, Short>{
		
		
		public FShort(FieldAccessor<T> field){
			this(field, false);
		}
		public FShort(FieldAccessor<T> field, boolean forceFixed){
			super(field, forceFixed, SHORT);
		}
		@Override
		protected EnumSet<NumberSize> allowedSizes(){
			var all=EnumSet.allOf(NumberSize.class);
			all.removeIf(s->s.greaterThan(SHORT));
			return all;
		}
		
		private short getValue(Struct.Pool<T> ioPool, T instance){
			return getAccessor().getShort(ioPool, instance);
		}
		
		public void setValue(Struct.Pool<T> ioPool, T instance, short value){
			getAccessor().setShort(ioPool, instance, value);
		}
		
		@Deprecated
		@Override
		public Short get(Struct.Pool<T> ioPool, T instance){
			return getValue(ioPool, instance);
		}
		@Deprecated
		@Override
		public void set(Struct.Pool<T> ioPool, T instance, Short value){
			setValue(ioPool, instance, value);
		}
		
		@Override
		public void write(Struct.Pool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
			var size=getSize(ioPool, instance);
			size.write(dest, getValue(ioPool, instance));
		}
		
		@Override
		public void read(Struct.Pool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
			var size=getSize(ioPool, instance);
			setValue(ioPool, instance, (short)size.read(src));
		}
		
		@Override
		public Optional<String> instanceToString(Struct.Pool<T> ioPool, T instance, boolean doShort, String start, String end, String fieldValueSeparator, String fieldSeparator){
			return Optional.of(String.valueOf(getValue(ioPool, instance)));
		}
		
		@Override
		public boolean instancesEqual(Struct.Pool<T> ioPool1, T inst1, Struct.Pool<T> ioPool2, T inst2){
			return getValue(ioPool1, inst1)==getValue(ioPool2, inst2);
		}
		@Override
		public int instanceHashCode(Struct.Pool<T> ioPool, T instance){
			return Short.hashCode(getValue(ioPool, instance));
		}
	}
	
	public static class FByte<T extends IOInstance<T>> extends IOFieldPrimitive<T, Byte>{
		
		public FByte(FieldAccessor<T> field){
			this(field, false);
		}
		
		public FByte(FieldAccessor<T> field, boolean forceFixed){
			super(field, forceFixed, BYTE);
		}
		@Override
		protected EnumSet<NumberSize> allowedSizes(){
			var all=EnumSet.allOf(NumberSize.class);
			all.removeIf(s->s.greaterThan(BYTE));
			return all;
		}
		
		public byte getValue(Struct.Pool<T> ioPool, T instance){
			return getAccessor().getByte(ioPool, instance);
		}
		
		public void setValue(Struct.Pool<T> ioPool, T instance, byte value){
			getAccessor().setByte(ioPool, instance, value);
		}
		
		@Deprecated
		@Override
		public Byte get(Struct.Pool<T> ioPool, T instance){
			return getValue(ioPool, instance);
		}
		@Deprecated
		@Override
		public void set(Struct.Pool<T> ioPool, T instance, Byte value){
			setValue(ioPool, instance, value);
		}
		
		@Override
		public void write(Struct.Pool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
			dest.writeInt1(getValue(ioPool, instance));
			
		}
		
		@Override
		public void read(Struct.Pool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
			setValue(ioPool, instance, src.readInt1());
		}
		
		@Override
		public Optional<String> instanceToString(Struct.Pool<T> ioPool, T instance, boolean doShort, String start, String end, String fieldValueSeparator, String fieldSeparator){
			return Optional.of(String.valueOf(getValue(ioPool, instance)));
		}
		
		@Override
		public boolean instancesEqual(Struct.Pool<T> ioPool1, T inst1, Struct.Pool<T> ioPool2, T inst2){
			return getValue(ioPool2, inst1)==getValue(ioPool1, inst2);
		}
		@Override
		public int instanceHashCode(Struct.Pool<T> ioPool, T instance){
			return Byte.hashCode(getValue(ioPool, instance));
		}
	}
	
	public static class FBoolean<T extends IOInstance<T>> extends IOField.Bit<T, Boolean>{
		
		protected FBoolean(FieldAccessor<T> field){
			super(field);
		}
		
		public boolean getValue(Struct.Pool<T> ioPool, T instance){
			return getAccessor().getBoolean(ioPool, instance);
		}
		
		public void setValue(Struct.Pool<T> ioPool, T instance, boolean value){
			getAccessor().setBoolean(ioPool, instance, value);
		}
		
		@Deprecated
		@Override
		public Boolean get(Struct.Pool<T> ioPool, T instance){
			return getValue(ioPool, instance);
		}
		@Deprecated
		@Override
		public void set(Struct.Pool<T> ioPool, T instance, Boolean value){
			setValue(ioPool, instance, value);
		}
		
		@Override
		public void writeBits(Struct.Pool<T> ioPool, BitWriter<?> dest, T instance) throws IOException{
			dest.writeBoolBit(getValue(ioPool, instance));
		}
		@Override
		public void readBits(Struct.Pool<T> ioPool, BitReader src, T instance) throws IOException{
			setValue(ioPool, instance, src.readBoolBit());
		}
		@Override
		public void skipReadBits(BitReader src, T instance) throws IOException{
			src.skip(1);
		}
		
		@Override
		public Optional<String> instanceToString(Struct.Pool<T> ioPool, T instance, boolean doShort, String start, String end, String fieldValueSeparator, String fieldSeparator){
			return Optional.of(String.valueOf(getValue(ioPool, instance)));
		}
		
		@Override
		public boolean instancesEqual(Struct.Pool<T> ioPool1, T inst1, Struct.Pool<T> ioPool2, T inst2){
			return getValue(ioPool1, inst1)==getValue(ioPool2, inst2);
		}
		@Override
		public int instanceHashCode(Struct.Pool<T> ioPool, T instance){
			return Boolean.hashCode(getValue(ioPool, instance));
		}
		
		@Override
		public SizeDescriptor<T> getSizeDescriptor(){
			return SizeDescriptor.Fixed.of(BIT, 1);
		}
	}
	
	private final boolean                                   forceFixed;
	private final NumberSize                                size;
	private       BiFunction<Struct.Pool<T>, T, NumberSize> dynamicSize;
	private       SizeDescriptor<T>                         sizeDescriptor;
	
	protected IOFieldPrimitive(FieldAccessor<T> field, boolean forceFixed, NumberSize size){
		super(field);
		this.forceFixed=forceFixed;
		this.size=size;
	}
	
	@Override
	public void init(){
		super.init();
		var fieldOpt=forceFixed?Optional.<IOField<T, NumberSize>>empty():IOFieldTools.getDynamicSize(getAccessor());
		var allowed =allowedSizes();
		if(fieldOpt.isPresent()){
			var field=fieldOpt.get();
			dynamicSize=(ioPool, instance)->{
				var val=field.get(ioPool, instance);
				if(!allowed.contains(val)) throw new IllegalStateException(val+" is not an allowed size in "+allowed+" at "+this+" with dynamic size "+field);
				return val;
			};
			sizeDescriptor=SizeDescriptor.Unknown.of(
				allowed.stream().min(Comparator.naturalOrder()).orElse(VOID),
				allowed.stream().max(Comparator.naturalOrder()),
				field.getAccessor());
		}else{
			sizeDescriptor=SizeDescriptor.Fixed.of(size.bytes);
		}
	}
	
	@Override
	public void skipRead(Struct.Pool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		var size=getSize(ioPool, instance);
		src.skipExact(size.bytes);
	}
	
	protected EnumSet<NumberSize> allowedSizes(){
		return EnumSet.allOf(NumberSize.class);
	}
	
	protected NumberSize getSize(Struct.Pool<T> ioPool, T instance){
		if(dynamicSize!=null) return dynamicSize.apply(ioPool, instance);
		return size;
	}
	
	@Override
	public SizeDescriptor<T> getSizeDescriptor(){
		return sizeDescriptor;
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public IOField<T, ValueType> implMaxAsFixedSize(){
		try{
			return (IOField<T, ValueType>)getClass().getConstructor(FieldAccessor.class, boolean.class).newInstance(getAccessor(), true);
		}catch(ReflectiveOperationException e){
			throw new RuntimeException(e);
		}
	}
}
