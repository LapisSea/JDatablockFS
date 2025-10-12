package com.lapissea.dfs.type.field.fields.reflection;

import com.lapissea.dfs.Utils;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.exceptions.FieldIsNull;
import com.lapissea.dfs.exceptions.MalformedStruct;
import com.lapissea.dfs.io.bit.BitReader;
import com.lapissea.dfs.io.bit.BitUtils;
import com.lapissea.dfs.io.bit.BitWriter;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.io.content.ContentWriter;
import com.lapissea.dfs.objects.NumberSize;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.GetAnnotation;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.SupportedPrimitive;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.field.BehaviourSupport;
import com.lapissea.dfs.type.field.FieldNames;
import com.lapissea.dfs.type.field.FieldSet;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.IOFieldTools;
import com.lapissea.dfs.type.field.SizeDescriptor;
import com.lapissea.dfs.type.field.VaryingSize;
import com.lapissea.dfs.type.field.access.FieldAccessor;
import com.lapissea.dfs.type.field.annotations.IODependency;
import com.lapissea.dfs.type.field.annotations.IODependency.VirtualNumSize;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.type.field.fields.BitField;
import com.lapissea.dfs.type.string.StringifySettings;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.dfs.utils.iterableplus.Match.Some;
import com.lapissea.jorth.CodeStream;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.util.UtilL;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;

import static com.lapissea.dfs.objects.NumberSize.*;
import static com.lapissea.dfs.type.WordSpace.BIT;

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
			
			if(List.of(FIntBoxed.class).contains(fieldType)){
				res.add(Behaviour.of(IONullability.class, BehaviourSupport::ioNullability));
			}
			if(List.of(FLong.class, FInt.class, FIntBoxed.class, FShort.class).contains(fieldType)){
				res.add(Behaviour.noop(IOValue.Unsigned.class));
			}
			if(!List.of(FByte.class, FBoolean.class).contains(fieldType)){
				res.add(Behaviour.justDeps(IODependency.NumSize.class, a -> Set.of(a.value())));
			}
			res.add(Behaviour.of(VirtualNumSize.class, (field, ann) -> {
				if(List.of(FByte.class, FBoolean.class).contains(fieldType)){
					throw new MalformedStruct("fmt", "{}#yellow is not allowed on {}#red", VirtualNumSize.class.getName(), fieldType.getName());
				}
				return BehaviourSupport.virtualNumSize(field, ann);
			}));
			return res;
		}
		@Override
		@SuppressWarnings("rawtypes")
		public Set<Class<? extends IOField>> listFieldTypes(){
			return Set.of(FDouble.class, FChar.class, FFloat.class, FLong.class, FInt.class, FIntBoxed.class, FShort.class, FByte.class, FBoolean.class);
		}
		
	}
	
	public static <T extends IOInstance<T>> IOField<T, ?> make(FieldAccessor<T> field){
		return SupportedPrimitive.get(field.getType()).map(t -> switch(t){
			case DOUBLE -> new FDouble<>(field, null);
			case CHAR -> new FChar<>(field, null);
			case FLOAT -> new FFloat<>(field, null);
			case LONG -> new FLong<>(field, null);
			case INT -> field.getType() == int.class? new FInt<>(field, null) : new FIntBoxed<>(field, null);
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
			size.writeInt(dest, val);
		}
		
		@Override
		public void read(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
			var size = getSize(ioPool, instance);
			setValue(ioPool, instance, (char)size.readInt(src));
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
	
	@SuppressWarnings("deprecation")
	public static final class FIntBoxed<T extends IOInstance<T>> extends FInt<T>{
		
		private IOFieldPrimitive.FBoolean<T> isNull;
		
		private FIntBoxed(FieldAccessor<T> field, VaryingSize size){
			super(field, size);
		}
		
		@Override
		public void init(FieldSet<T> fields){
			super.init(fields);
			if(nullable()){
				isNull = fields.requireExactBoolean(FieldNames.nullFlag(getAccessor()));
			}
		}
		
		@Override
		public List<ValueGeneratorInfo<T, ?>> getGenerators(){
			if(!nullable()) return super.getGenerators();
			
			return Utils.concat(super.getGenerators(), new ValueGeneratorInfo<>(isNull, new ValueGenerator.NoCheck<T, Boolean>(){
				@Override
				public Boolean generate(VarPool<T> ioPool, DataProvider provider, T instance, boolean allowExternalMod){
					return get(ioPool, instance) == null;
				}
			}));
		}
		
		@Override
		protected IOField<T, Integer> withVaryingSize(VaryingSize size){ return new FIntBoxed<>(getAccessor(), size); }
		
		@Override
		public Integer get(VarPool<T> ioPool, T instance){
			return getNullable(ioPool, instance);
		}
		@Override
		public void set(VarPool<T> ioPool, T instance, Integer value){
			getAccessor().set(ioPool, instance, value);
		}
		
		@Override
		public void write(VarPool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
			var val = get(ioPool, instance);
			if(val == null){
				if(nullable()){
					var size = getSafeSize(ioPool, instance, VOID);
					dest.writeWord(0, size.bytes);
				}else{
					throw new FieldIsNull(this);
				}
				return;
			}
			super.write(ioPool, provider, dest, instance);
		}
		
		@Override
		public void read(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
			if(nullable() && isNull.getValue(ioPool, instance)){
				set(ioPool, instance, null);
				src.skip(getSafeSize(ioPool, instance, VOID));
				return;
			}
			super.read(ioPool, provider, src, instance, genericContext);
		}
		@Override
		public Optional<String> instanceToString(VarPool<T> ioPool, T instance, boolean doShort){
			var val = get(ioPool, instance);
			if(val == null || val == 0) return Optional.empty();
			return Optional.of(String.valueOf(val));
		}
		@Override
		public boolean instancesEqual(VarPool<T> ioPool1, T inst1, VarPool<T> ioPool2, T inst2){
			return Objects.equals(get(ioPool1, inst1), get(ioPool2, inst2));
		}
		@Override
		public int instanceHashCode(VarPool<T> ioPool, T instance){
			var val = get(ioPool, instance);
			return val == null? Integer.MAX_VALUE : Integer.hashCode(val);
		}
		
		@Override
		public void injectReadField(CodeStream writer, AccessMap accessMap) throws MalformedJorth, AccessMap.ConstantNeeded{
			var dynamicSize = getDynamicSize();
			if(dynamicSize == null){
				var readIntName = "read" + (unsigned? "UnsignedInt" : "Int") + maxSize.size.bytes;
				if(unsigned && maxSize.size == INT){
					readIntName += " cast int";
				}
				accessMap.preSet(getAccessor(), writer);
				writer.write(
					"""
						static call #Integer valueOf start
							get #arg src
							call {}
						end
						""",
					readIntName
				);
				accessMap.set(getAccessor(), writer);
			}else{
				accessMap.preSet(getAccessor(), writer);
				writer.write("static call #Integer valueOf start");
				accessMap.get(dynamicSize.field.getAccessor(), writer);
				var fnName = unsigned? "readInt" : "readIntSigned";
				writer.write(
					"""
						call {} start
							get #arg src
						end
						""", fnName);
				writer.wEnd();
				accessMap.set(getAccessor(), writer);
			}
			if(nullable()){
				accessMap.get(isNull.getAccessor(), writer);
				writer.write("if start");
				accessMap.preSet(getAccessor(), writer);
				writer.write("null start #Integer end");
				accessMap.set(getAccessor(), writer);
				writer.wEnd();
			}
		}
	}
	
	public static sealed class FInt<T extends IOInstance<T>> extends IOFieldPrimitive<T, Integer> implements SpecializedGenerator{
		
		protected final boolean unsigned;
		
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
		protected IOField<T, Integer> withVaryingSize(VaryingSize size){ return new FInt<>(getAccessor(), size); }
		
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
				size.writeInt(dest, val);
			}else{
				size.writeIntSigned(dest, val);
			}
		}
		
		@Override
		public void read(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
			var size = getSize(ioPool, instance);
			int val;
			if(unsigned){
				val = size.readInt(src);
			}else{
				val = size.readIntSigned(src);
			}
			setValue(ioPool, instance, val);
		}
		
		@Override
		public void injectReadField(CodeStream writer, AccessMap accessMap) throws MalformedJorth, AccessMap.ConstantNeeded{
			var dynamicSize = getDynamicSize();
			if(dynamicSize == null){
				var readIntName = "read" + (unsigned? "UnsignedInt" : "Int") + maxSize.size.bytes;
				if(unsigned && maxSize.size == INT){
					readIntName += " cast int";
				}
				accessMap.preSet(getAccessor(), writer);
				writer.write(
					"""
						get #arg src
						call {}
						""",
					readIntName
				);
				accessMap.set(getAccessor(), writer);
			}else{
				accessMap.preSet(getAccessor(), writer);
				accessMap.get(dynamicSize.field.getAccessor(), writer);
				var fnName = unsigned? "readInt" : "readIntSigned";
				writer.write(
					"""
						call {} start
							get #arg src
						end
						""", fnName);
				accessMap.set(getAccessor(), writer);
			}
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
				size.writeInt(dest, val);
			}else{
				size.writeIntSigned(dest, val);
			}
		}
		
		@Override
		public void read(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
			var   size = getSize(ioPool, instance);
			short val;
			if(unsigned){
				val = (short)size.readInt(src);
			}else{
				val = (short)size.readIntSigned(src);
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
	
	public static final class FBoolean<T extends IOInstance<T>> extends BitField<T, Boolean> implements SpecializedGenerator{
		
		private FBoolean(FieldAccessor<T> field){
			super(field, SizeDescriptor.Fixed.of(BIT, 1));
		}
		
		@Override
		protected Set<TypeFlag> computeTypeFlags(){
			return Set.of(TypeFlag.PRIMITIVE_OR_ENUM);
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
		public Optional<String> instanceToString(VarPool<T> ioPool, T instance, StringifySettings settings){
			return instanceToString(ioPool, instance, settings.doShort());
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
		
		@Override
		public void injectReadField(CodeStream writer, AccessMap accessMap) throws MalformedJorth, AccessMap.ConstantNeeded{
			
			accessMap.preSet(getAccessor(), writer);
			writer.write(
				"""
					get #arg src
					call readUnsignedInt1
					""");
			
			//Check integrity bits
			var checkBits = BitUtils.makeMask(7)<<1;
			writer.write(
				"""
					static call {} checkFlag start
						dup
						{}
					end
					if not start
						new {} start 'Illegal boolean integrity bits' end
						throw
					end
					""", UtilL.class, checkBits, IOException.class
			);
			
			writer.write(
				"""
					cast boolean
					"""
			);
			accessMap.set(getAccessor(), writer);
		}
	}
	
	private final   boolean          forceFixed;
	protected final VaryingSize      maxSize;
	private         DynamicFieldSize dynamicSize;
	protected final boolean          boxed;
	
	protected IOFieldPrimitive(FieldAccessor<T> field, VaryingSize maxSize){
		super(field);
		var maxAllowed = maxAllowed();
		
		if(maxSize != null && maxSize.size.greaterThan(maxAllowed)) throw new IllegalArgumentException(maxSize + " > " + maxAllowed);
		this.forceFixed = maxSize != null;
		
		this.maxSize = maxSize != null? maxSize : new VaryingSize(maxAllowed, -1);
		boxed = !field.getType().isPrimitive();
	}
	
	protected final class DynamicFieldSize implements BiFunction<VarPool<T>, T, NumberSize>{
		protected final IOField<T, NumberSize> field;
		private final   EnumSet<NumberSize>    allowed;
		private DynamicFieldSize(IOField<T, NumberSize> field, EnumSet<NumberSize> allowed){
			this.field = field;
			this.allowed = allowed;
		}
		@Override
		public NumberSize apply(VarPool<T> ioPool, T instance){
			var val = field.get(ioPool, instance);
			if(!allowed.contains(val)) failSize(val);
			return val;
		}
		private void failSize(NumberSize val){
			throw new IllegalStateException(val + " is not an allowed size in " + allowed + " at " + IOFieldPrimitive.this + " with dynamic size " + field);
		}
	}
	
	protected DynamicFieldSize getDynamicSize(){
		return dynamicSize;
	}
	
	@Override
	public void init(FieldSet<T> fields){
		super.init(fields);
		
		if(!forceFixed && IOFieldTools.getDynamicSize(getAccessor()) instanceof Some(var field)){
			var allowed = allowedSizes();
			allowed.removeIf(s -> s.greaterThan(maxSize.size));
			dynamicSize = new DynamicFieldSize(field, allowed);
			initSizeDescriptor(SizeDescriptor.Unknown.of(
				Iters.from(allowed).min().orElse(VOID),
				Iters.from(allowed).max().opt(),
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
		return Iters.from(allowedSizes()).reduce(NumberSize::max).orElseThrow();
	}
	
	protected final NumberSize getSafeSize(VarPool<T> ioPool, T instance, boolean unsigned, long num){
		return getSafeSize(ioPool, instance, NumberSize.bySize(num, unsigned));
	}
	protected final NumberSize getSafeSize(VarPool<T> ioPool, T instance, NumberSize neededNum){
		if(dynamicSize != null) return dynamicSize.apply(ioPool, instance);
		return maxSize.safeSize(neededNum);
	}
	protected final NumberSize getSize(VarPool<T> ioPool, T instance){
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
	public final Optional<String> instanceToString(VarPool<T> ioPool, T instance, StringifySettings settings){
		return instanceToString(ioPool, instance, settings.doShort());
	}
	
	@Override
	protected Set<TypeFlag> computeTypeFlags(){
		return Set.of(TypeFlag.PRIMITIVE_OR_ENUM);
	}
}
