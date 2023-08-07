package com.lapissea.cfs.type.field.fields.reflection;

import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.exceptions.MalformedStruct;
import com.lapissea.cfs.io.bit.BitReader;
import com.lapissea.cfs.io.bit.BitWriter;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.GetAnnotation;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.SupportedPrimitive;
import com.lapissea.cfs.type.VarPool;
import com.lapissea.cfs.type.field.BehaviourSupport;
import com.lapissea.cfs.type.field.FieldSet;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.VaryingSize;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.cfs.type.field.annotations.IODependency;
import com.lapissea.cfs.type.field.annotations.IODependency.VirtualNumSize;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.cfs.type.field.fields.BitField;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import static com.lapissea.cfs.objects.NumberSize.*;
import static com.lapissea.cfs.type.WordSpace.BIT;

public abstract sealed class IOFieldPrimitive<T extends IOInstance<T>, ValueType> extends IOField<T, ValueType>{
	
	@SuppressWarnings("unused")
	private static final class Usage implements FieldUsage{
		@Override
		public boolean isCompatible(Type type, GetAnnotation annotations){
			return SupportedPrimitive.isAny(type);
		}
		@Override
		public <T extends IOInstance<T>> IOField<T, ?> create(FieldAccessor<T> field){
			return IOFieldPrimitive.make(field);
		}
		@Override
		public <T extends IOInstance<T>> List<Behaviour<?, T>> annotationBehaviour(Class<IOField<T, ?>> fieldType){
			var res = new ArrayList<Behaviour<?, T>>(3);
			
			if(List.of(FLong.class, FInt.class, FShort.class).contains(fieldType)) res.add(Behaviour.noop(IOValue.Unsigned.class));
			if(!List.of(FByte.class, FBoolean.class).contains(fieldType)) res.add(Behaviour.justDeps(IODependency.NumSize.class, a -> Set.of(a.value())));
			res.add(Behaviour.of(VirtualNumSize.class, (field, ann) -> {
				if(List.of(FByte.class, FBoolean.class).contains(fieldType)){
					throw new MalformedStruct(VirtualNumSize.class.getName() + " is not allowed on " + fieldType.getName());
				}
				return BehaviourSupport.virtualNumSize(field, ann);
			}));
			return res;
		}
		@Override
		@SuppressWarnings("rawtypes")
		public Set<Class<? extends IOField>> listFieldTypes(){
			return Set.of(FDouble.class, FChar.class, FFloat.class, FLong.class, FInt.class, FShort.class, FByte.class, FBoolean.class);
		}
	}
	
	public static <T extends IOInstance<T>> IOField<T, ?> make(FieldAccessor<T> field){
		return SupportedPrimitive.get(field.getType()).map(t -> switch(t){
			case DOUBLE -> new FDouble<>(field, null);
			case CHAR -> new FChar<>(field, null);
			case FLOAT -> new FFloat<>(field, null);
			case LONG -> new FLong<>(field, null);
			case INT -> new FInt<>(field, null);
			case SHORT -> new FShort<>(field, null);
			case BYTE -> new FByte<>(field, null);
			case BOOLEAN -> new FBoolean<>(field);
		}).orElseThrow(() -> new IllegalArgumentException(field.getType().getName() + " is not a primitive"));
	}
	
	public static final class FDouble<T extends IOInstance<T>> extends IOFieldPrimitive<T, Double>{
		
		private FDouble(FieldAccessor<T> field, VaryingSize size){ super(field, size); }
		
		@Override
		protected EnumSet<NumberSize> allowedSizes(){
			return EnumSet.of(VOID, SHORT, INT, LONG);
		}
		@Override
		protected IOField<T, Double> withVaryingSize(VaryingSize size){
			return new FDouble<>(getAccessor(), size);
		}
		
		private double getValue(VarPool<T> ioPool, T instance){
			return getAccessor().getDouble(ioPool, instance);
		}
		
		public void setValue(VarPool<T> ioPool, T instance, double value){
			getAccessor().setDouble(ioPool, instance, value);
		}
		
		@Deprecated
		@Override
		public Double get(VarPool<T> ioPool, T instance){
			return getValue(ioPool, instance);
		}
		@Deprecated
		@Override
		public void set(VarPool<T> ioPool, T instance, Double value){
			setValue(ioPool, instance, value);
		}
		
		@Override
		public void write(VarPool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
			var size = getSafeSize(ioPool, instance, LONG);
			size.writeFloating(dest, getValue(ioPool, instance));
		}
		
		@Override
		public void read(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
			var size = getSize(ioPool, instance);
			setValue(ioPool, instance, size.readFloating(src));
		}
		
		@Override
		public Optional<String> instanceToString(VarPool<T> ioPool, T instance, boolean doShort){
			var val = getValue(ioPool, instance);
			if(val == 0) return Optional.empty();
			return Optional.of(String.valueOf(val));
		}
		
		@Override
		public boolean instancesEqual(VarPool<T> ioPool1, T inst1, VarPool<T> ioPool2, T inst2){
			return getValue(ioPool1, inst1) == getValue(ioPool2, inst2);
		}
		@Override
		public int instanceHashCode(VarPool<T> ioPool, T instance){
			return Double.hashCode(getValue(ioPool, instance));
		}
	}
	
	public static final class FChar<T extends IOInstance<T>> extends IOFieldPrimitive<T, Character>{
		
		private FChar(FieldAccessor<T> field, VaryingSize size){ super(field, size); }
		
		@Override
		protected EnumSet<NumberSize> allowedSizes(){
			return EnumSet.of(VOID, BYTE, SHORT);
		}
		@Override
		protected IOField<T, Character> withVaryingSize(VaryingSize size){
			return new FChar<>(getAccessor(), size);
		}
		
		private char getValue(VarPool<T> ioPool, T instance){
			return getAccessor().getChar(ioPool, instance);
		}
		
		public void setValue(VarPool<T> ioPool, T instance, char value){
			getAccessor().setChar(ioPool, instance, value);
		}
		
		@Deprecated
		@Override
		public Character get(VarPool<T> ioPool, T instance){
			return getValue(ioPool, instance);
		}
		@Deprecated
		@Override
		public void set(VarPool<T> ioPool, T instance, Character value){
			setValue(ioPool, instance, value);
		}
		
		@Override
		public void write(VarPool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
			var val  = getValue(ioPool, instance);
			var size = getSafeSize(ioPool, instance, true, val);
			size.writeFloating(dest, val);
		}
		
		@Override
		public void read(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
			var size = getSize(ioPool, instance);
			setValue(ioPool, instance, (char)size.read(src));
		}
		
		@Override
		public Optional<String> instanceToString(VarPool<T> ioPool, T instance, boolean doShort){
			return Optional.of(String.valueOf(getValue(ioPool, instance)));
		}
		
		@Override
		public boolean instancesEqual(VarPool<T> ioPool1, T inst1, VarPool<T> ioPool2, T inst2){
			return getValue(ioPool1, inst1) == getValue(ioPool2, inst2);
		}
		@Override
		public int instanceHashCode(VarPool<T> ioPool, T instance){
			return Character.hashCode(getValue(ioPool, instance));
		}
	}
	
	public static final class FFloat<T extends IOInstance<T>> extends IOFieldPrimitive<T, Float>{
		
		private FFloat(FieldAccessor<T> field, VaryingSize size){ super(field, size); }
		
		@Override
		protected EnumSet<NumberSize> allowedSizes(){
			return EnumSet.of(VOID, SHORT, INT);
		}
		@Override
		protected IOField<T, Float> withVaryingSize(VaryingSize size){
			return new FFloat<>(getAccessor(), size);
		}
		
		private float getValue(VarPool<T> ioPool, T instance){
			return getAccessor().getFloat(ioPool, instance);
		}
		
		public void setValue(VarPool<T> ioPool, T instance, float value){
			getAccessor().setFloat(ioPool, instance, value);
		}
		
		@Deprecated
		@Override
		public Float get(VarPool<T> ioPool, T instance){
			return getValue(ioPool, instance);
		}
		@Deprecated
		@Override
		public void set(VarPool<T> ioPool, T instance, Float value){
			setValue(ioPool, instance, value);
		}
		
		@Override
		public void write(VarPool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
			var size = getSafeSize(ioPool, instance, INT);
			size.writeFloating(dest, getValue(ioPool, instance));
		}
		
		@Override
		public void read(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
			var size = getSize(ioPool, instance);
			setValue(ioPool, instance, (float)size.readFloating(src));
		}
		
		@Override
		public Optional<String> instanceToString(VarPool<T> ioPool, T instance, boolean doShort){
			var val = getValue(ioPool, instance);
			if(val == 0) return Optional.empty();
			return Optional.of(String.valueOf(val));
		}
		
		@Override
		public boolean instancesEqual(VarPool<T> ioPool1, T inst1, VarPool<T> ioPool2, T inst2){
			return getValue(ioPool1, inst1) == getValue(ioPool2, inst2);
		}
		@Override
		public int instanceHashCode(VarPool<T> ioPool, T instance){
			return Float.hashCode(getValue(ioPool, instance));
		}
	}
	
	public static final class FLong<T extends IOInstance<T>> extends IOFieldPrimitive<T, Long>{
		
		private final boolean unsigned;
		
		private FLong(FieldAccessor<T> field, VaryingSize size){
			super(field, size);
			unsigned = field.hasAnnotation(IOValue.Unsigned.class);
		}
		
		@Override
		protected EnumSet<NumberSize> allowedSizes(){
			return EnumSet.allOf(NumberSize.class);
		}
		@Override
		protected IOField<T, Long> withVaryingSize(VaryingSize size){
			return new FLong<>(getAccessor(), size);
		}
		
		public long getValue(VarPool<T> ioPool, T instance){
			return getAccessor().getLong(ioPool, instance);
		}
		
		public void setValue(VarPool<T> ioPool, T instance, long value){
			getAccessor().setLong(ioPool, instance, value);
		}
		
		@Deprecated
		@Override
		public Long get(VarPool<T> ioPool, T instance){
			return getValue(ioPool, instance);
		}
		@Deprecated
		@Override
		public void set(VarPool<T> ioPool, T instance, Long value){
			setValue(ioPool, instance, value);
		}
		
		@Override
		public void write(VarPool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
			var val  = getValue(ioPool, instance);
			var size = getSafeSize(ioPool, instance, unsigned, val);
			if(unsigned){
				size.write(dest, val);
			}else{
				size.writeSigned(dest, val);
			}
		}
		
		@Override
		public void read(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
			var  size = getSize(ioPool, instance);
			long val;
			if(unsigned){
				val = size.read(src);
			}else{
				val = size.readSigned(src);
			}
			setValue(ioPool, instance, val);
		}
		
		@Override
		public Optional<String> instanceToString(VarPool<T> ioPool, T instance, boolean doShort){
			var val = getValue(ioPool, instance);
			if(val == 0) return Optional.empty();
			return Optional.of(String.valueOf(val));
		}
		
		@Override
		public boolean instancesEqual(VarPool<T> ioPool1, T inst1, VarPool<T> ioPool2, T inst2){
			return getValue(ioPool1, inst1) == getValue(ioPool2, inst2);
		}
		@Override
		public int instanceHashCode(VarPool<T> ioPool, T instance){
			return Long.hashCode(getValue(ioPool, instance));
		}
	}
	
	public static final class FInt<T extends IOInstance<T>> extends IOFieldPrimitive<T, Integer>{
		
		private final boolean unsigned;
		
		private FInt(FieldAccessor<T> field, VaryingSize size){
			super(field, size);
			unsigned = field.hasAnnotation(IOValue.Unsigned.class);
		}
		@Override
		protected EnumSet<NumberSize> allowedSizes(){
			var all = EnumSet.allOf(NumberSize.class);
			all.removeIf(s -> s.greaterThan(INT));
			return all;
		}
		@Override
		protected IOField<T, Integer> withVaryingSize(VaryingSize size){
			return new FInt<>(getAccessor(), size);
		}
		
		public int getValue(VarPool<T> ioPool, T instance){
			return getAccessor().getInt(ioPool, instance);
		}
		
		public void setValue(VarPool<T> ioPool, T instance, int value){
			getAccessor().setInt(ioPool, instance, value);
		}
		
		@Deprecated
		@Override
		public Integer get(VarPool<T> ioPool, T instance){
			return getValue(ioPool, instance);
		}
		@Deprecated
		@Override
		public void set(VarPool<T> ioPool, T instance, Integer value){
			setValue(ioPool, instance, value);
		}
		
		@Override
		public void write(VarPool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
			var val  = getValue(ioPool, instance);
			var size = getSafeSize(ioPool, instance, unsigned, val);
			if(unsigned){
				size.write(dest, val);
			}else{
				size.writeSigned(dest, val);
			}
		}
		
		@Override
		public void read(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
			var size = getSize(ioPool, instance);
			int val;
			if(unsigned){
				val = (int)size.read(src);
			}else{
				val = (int)size.readSigned(src);
			}
			setValue(ioPool, instance, val);
		}
		
		@Override
		public Optional<String> instanceToString(VarPool<T> ioPool, T instance, boolean doShort){
			var val = getValue(ioPool, instance);
			if(val == 0) return Optional.empty();
			return Optional.of(String.valueOf(val));
		}
		
		@Override
		public boolean instancesEqual(VarPool<T> ioPool1, T inst1, VarPool<T> ioPool2, T inst2){
			return getValue(ioPool1, inst1) == getValue(ioPool2, inst2);
		}
		@Override
		public int instanceHashCode(VarPool<T> ioPool, T instance){
			return Integer.hashCode(getValue(ioPool, instance));
		}
	}
	
	public static final class FShort<T extends IOInstance<T>> extends IOFieldPrimitive<T, Short>{
		
		private final boolean unsigned;
		
		private FShort(FieldAccessor<T> field, VaryingSize size){
			super(field, size);
			unsigned = field.hasAnnotation(IOValue.Unsigned.class);
		}
		@Override
		protected EnumSet<NumberSize> allowedSizes(){
			var all = EnumSet.allOf(NumberSize.class);
			all.removeIf(s -> s.greaterThan(SHORT));
			return all;
		}
		@Override
		protected IOField<T, Short> withVaryingSize(VaryingSize size){
			return new FShort<>(getAccessor(), size);
		}
		
		private short getValue(VarPool<T> ioPool, T instance){
			return getAccessor().getShort(ioPool, instance);
		}
		
		public void setValue(VarPool<T> ioPool, T instance, short value){
			getAccessor().setShort(ioPool, instance, value);
		}
		
		@Deprecated
		@Override
		public Short get(VarPool<T> ioPool, T instance){
			return getValue(ioPool, instance);
		}
		@Deprecated
		@Override
		public void set(VarPool<T> ioPool, T instance, Short value){
			setValue(ioPool, instance, value);
		}
		
		@Override
		public void write(VarPool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
			var val  = getValue(ioPool, instance);
			var size = getSafeSize(ioPool, instance, unsigned, val);
			if(unsigned){
				size.write(dest, val);
			}else{
				size.writeSigned(dest, val);
			}
		}
		
		@Override
		public void read(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
			var   size = getSize(ioPool, instance);
			short val;
			if(unsigned){
				val = (short)size.read(src);
			}else{
				val = (short)size.readSigned(src);
			}
			setValue(ioPool, instance, val);
		}
		
		@Override
		public Optional<String> instanceToString(VarPool<T> ioPool, T instance, boolean doShort){
			var val = getValue(ioPool, instance);
			if(val == 0) return Optional.empty();
			return Optional.of(String.valueOf(val));
		}
		
		@Override
		public boolean instancesEqual(VarPool<T> ioPool1, T inst1, VarPool<T> ioPool2, T inst2){
			return getValue(ioPool1, inst1) == getValue(ioPool2, inst2);
		}
		@Override
		public int instanceHashCode(VarPool<T> ioPool, T instance){
			return Short.hashCode(getValue(ioPool, instance));
		}
	}
	
	public static final class FByte<T extends IOInstance<T>> extends IOFieldPrimitive<T, Byte>{
		
		private FByte(FieldAccessor<T> field, VaryingSize size){ super(field, size); }
		
		@Override
		protected EnumSet<NumberSize> allowedSizes(){
			return EnumSet.of(BYTE);
		}
		@Override
		protected IOField<T, Byte> withVaryingSize(VaryingSize size){
			return new FByte<>(getAccessor(), size);
		}
		
		public byte getValue(VarPool<T> ioPool, T instance){
			return getAccessor().getByte(ioPool, instance);
		}
		
		public void setValue(VarPool<T> ioPool, T instance, byte value){
			getAccessor().setByte(ioPool, instance, value);
		}
		
		@Deprecated
		@Override
		public Byte get(VarPool<T> ioPool, T instance){
			return getValue(ioPool, instance);
		}
		@Deprecated
		@Override
		public void set(VarPool<T> ioPool, T instance, Byte value){
			setValue(ioPool, instance, value);
		}
		
		@Override
		public void write(VarPool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
			maxSize.safeSize(BYTE);
			dest.writeInt1(getValue(ioPool, instance));
		}
		
		@Override
		public void read(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
			setValue(ioPool, instance, src.readInt1());
		}
		
		@Override
		public Optional<String> instanceToString(VarPool<T> ioPool, T instance, boolean doShort){
			var val = getValue(ioPool, instance);
			if(val == 0) return Optional.empty();
			return Optional.of(String.valueOf(val));
		}
		
		@Override
		public boolean instancesEqual(VarPool<T> ioPool1, T inst1, VarPool<T> ioPool2, T inst2){
			return getValue(ioPool2, inst1) == getValue(ioPool1, inst2);
		}
		@Override
		public int instanceHashCode(VarPool<T> ioPool, T instance){
			return Byte.hashCode(getValue(ioPool, instance));
		}
	}
	
	public static final class FBoolean<T extends IOInstance<T>> extends BitField<T, Boolean>{
		
		private FBoolean(FieldAccessor<T> field){
			super(field, SizeDescriptor.Fixed.of(BIT, 1));
		}
		
		public boolean getValue(VarPool<T> ioPool, T instance){
			return getAccessor().getBoolean(ioPool, instance);
		}
		
		public void setValue(VarPool<T> ioPool, T instance, boolean value){
			getAccessor().setBoolean(ioPool, instance, value);
		}
		
		@Deprecated
		@Override
		public Boolean get(VarPool<T> ioPool, T instance){
			return getValue(ioPool, instance);
		}
		@Deprecated
		@Override
		public void set(VarPool<T> ioPool, T instance, Boolean value){
			setValue(ioPool, instance, value);
		}
		
		@Override
		public void writeBits(VarPool<T> ioPool, BitWriter<?> dest, T instance) throws IOException{
			dest.writeBoolBit(getValue(ioPool, instance));
		}
		@Override
		public void readBits(VarPool<T> ioPool, BitReader src, T instance) throws IOException{
			setValue(ioPool, instance, src.readBoolBit());
		}
		@Override
		public void skipReadBits(BitReader src, T instance) throws IOException{
			src.skip(1);
		}
		
		@Override
		public Optional<String> instanceToString(VarPool<T> ioPool, T instance, boolean doShort, String start, String end, String fieldValueSeparator, String fieldSeparator){
			return instanceToString(ioPool, instance, doShort);
		}
		@Override
		public Optional<String> instanceToString(VarPool<T> ioPool, T instance, boolean doShort){
			return Optional.of(String.valueOf(getValue(ioPool, instance)));
		}
		
		@Override
		public boolean instancesEqual(VarPool<T> ioPool1, T inst1, VarPool<T> ioPool2, T inst2){
			return getValue(ioPool1, inst1) == getValue(ioPool2, inst2);
		}
		@Override
		public int instanceHashCode(VarPool<T> ioPool, T instance){
			return Boolean.hashCode(getValue(ioPool, instance));
		}
	}
	
	private final   boolean                               forceFixed;
	protected final VaryingSize                           maxSize;
	private         BiFunction<VarPool<T>, T, NumberSize> dynamicSize;
	
	protected IOFieldPrimitive(FieldAccessor<T> field, VaryingSize maxSize){
		super(field);
		var maxAllowed = maxAllowed();
		
		if(maxSize != null && maxSize.size.greaterThan(maxAllowed)) throw new IllegalArgumentException(maxSize + " > " + maxAllowed);
		this.forceFixed = maxSize != null;
		
		this.maxSize = maxSize != null? maxSize : new VaryingSize(maxAllowed, -1);
	}
	
	@Override
	public void init(FieldSet<T> fields){
		super.init(fields);
		var fieldOpt = forceFixed? Optional.<IOField<T, NumberSize>>empty() : IOFieldTools.getDynamicSize(getAccessor());
		if(fieldOpt.isPresent()){
			var allowed = EnumSet.copyOf(allowedSizes().stream().filter(s -> s.lesserThanOrEqual(maxSize.size)).collect(Collectors.toSet()));
			var field   = fieldOpt.get();
			dynamicSize = (ioPool, instance) -> {
				var val = field.get(ioPool, instance);
				if(!allowed.contains(val)) throw new IllegalStateException(val + " is not an allowed size in " + allowed + " at " + this + " with dynamic size " + field);
				return val;
			};
			initSizeDescriptor(SizeDescriptor.Unknown.of(
				allowed.stream().min(Comparator.naturalOrder()).orElse(VOID),
				allowed.stream().max(Comparator.naturalOrder()),
				field.getAccessor()));
		}else{
			initSizeDescriptor(SizeDescriptor.Fixed.of(maxSize.size.bytes));
		}
	}
	
	@Override
	public void skip(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		var size = getSize(ioPool, instance);
		size.skip(src);
	}
	
	protected abstract EnumSet<NumberSize> allowedSizes();
	
	private NumberSize maxAllowed(){
		return allowedSizes().stream().reduce(NumberSize::max).orElseThrow();
	}
	
	protected NumberSize getSafeSize(VarPool<T> ioPool, T instance, boolean unsigned, long num){
		return getSafeSize(ioPool, instance, NumberSize.bySize(num, unsigned));
	}
	protected NumberSize getSafeSize(VarPool<T> ioPool, T instance, NumberSize neededNum){
		if(dynamicSize != null) return dynamicSize.apply(ioPool, instance);
		return maxSize.safeSize(neededNum);
	}
	protected NumberSize getSize(VarPool<T> ioPool, T instance){
		if(dynamicSize != null) return dynamicSize.apply(ioPool, instance);
		return maxSize.size;
	}
	
	@Override
	public IOField<T, ValueType> maxAsFixedSize(VaryingSize.Provider varProvider){
		String uid  = sizeDescriptorSafe() instanceof SizeDescriptor.UnknownNum<T> num? num.getAccessor().getName() : null;
		var    size = varProvider.provide(maxAllowed(), uid, false);
		if(forceFixed && maxSize == size) return this;
		return withVaryingSize(size);
	}
	protected abstract IOField<T, ValueType> withVaryingSize(VaryingSize size);
	
	@Override
	public final Optional<String> instanceToString(VarPool<T> ioPool, T instance, boolean doShort, String start, String end, String fieldValueSeparator, String fieldSeparator){
		return instanceToString(ioPool, instance, doShort);
	}
}
