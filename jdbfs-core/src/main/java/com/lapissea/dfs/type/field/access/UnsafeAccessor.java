package com.lapissea.dfs.type.field.access;

import com.lapissea.dfs.internal.MyUnsafe;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.field.IOFieldTools;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static com.lapissea.dfs.internal.MyUnsafe.UNSAFE;

public sealed class UnsafeAccessor<CTyp extends IOInstance<CTyp>> extends ExactFieldAccessor<CTyp>{
	
	public static sealed class Funct<CTyp extends IOInstance<CTyp>> extends UnsafeAccessor<CTyp>{
		
		private final Function<CTyp, ?>        getter;
		private final BiConsumer<CTyp, Object> setter;
		
		public Funct(Struct<CTyp> struct, Field field, Optional<Method> getter, Optional<Method> setter, String name, Type genericType){
			super(struct, field, name, genericType);
			
			getter.ifPresent(get -> validateGetter(genericType, get));
			setter.ifPresent(set -> validateSetter(genericType, set));
			
			this.getter = getter.map(ExactFieldAccessor::findParent).map(this::makeGetter).orElse(null);
			this.setter = setter.map(ExactFieldAccessor::findParent).map(this::makeSetter).orElse(null);
		}
		
		@Override
		protected void setExactShort(VarPool<CTyp> ioPool, CTyp instance, short value){
			if(setter != null) setter.accept(instance, value);
			else super.setExactShort(ioPool, instance, value);
		}
		@Override
		protected short getExactShort(VarPool<CTyp> ioPool, CTyp instance){
			if(getter != null) return (short)getter.apply(instance);
			else return super.getExactShort(ioPool, instance);
		}
		
		@Override
		protected void setExactChar(VarPool<CTyp> ioPool, CTyp instance, char value){
			if(setter != null) setter.accept(instance, value);
			else super.setExactChar(ioPool, instance, value);
		}
		@Override
		protected char getExactChar(VarPool<CTyp> ioPool, CTyp instance){
			if(getter != null) return (char)getter.apply(instance);
			else return super.getExactChar(ioPool, instance);
		}
		
		@Override
		protected void setExactLong(VarPool<CTyp> ioPool, CTyp instance, long value){
			if(setter != null) setter.accept(instance, value);
			else super.setExactLong(ioPool, instance, value);
		}
		@Override
		protected long getExactLong(VarPool<CTyp> ioPool, CTyp instance){
			if(getter != null) return (long)getter.apply(instance);
			else return super.getExactLong(ioPool, instance);
		}
		
		@Override
		protected void setExactByte(VarPool<CTyp> ioPool, CTyp instance, byte value){
			if(setter != null) setter.accept(instance, value);
			else super.setExactByte(ioPool, instance, value);
		}
		@Override
		protected byte getExactByte(VarPool<CTyp> ioPool, CTyp instance){
			if(getter != null) return (byte)getter.apply(instance);
			else return super.getExactByte(ioPool, instance);
		}
		
		@Override
		protected void setExactInt(VarPool<CTyp> ioPool, CTyp instance, int value){
			if(setter != null) setter.accept(instance, value);
			else super.setExactInt(ioPool, instance, value);
		}
		@Override
		protected int getExactInt(VarPool<CTyp> ioPool, CTyp instance){
			if(getter != null) return (int)getter.apply(instance);
			else return super.getExactInt(ioPool, instance);
		}
		
		@Override
		protected void setExactDouble(VarPool<CTyp> ioPool, CTyp instance, double value){
			if(setter != null) setter.accept(instance, value);
			else super.setExactDouble(ioPool, instance, value);
		}
		@Override
		protected double getExactDouble(VarPool<CTyp> ioPool, CTyp instance){
			if(getter != null) return (double)getter.apply(instance);
			else return super.getExactDouble(ioPool, instance);
		}
		
		@Override
		protected void setExactFloat(VarPool<CTyp> ioPool, CTyp instance, float value){
			if(setter != null) setter.accept(instance, value);
			else super.setExactFloat(ioPool, instance, value);
		}
		@Override
		protected float getExactFloat(VarPool<CTyp> ioPool, CTyp instance){
			if(getter != null) return (float)getter.apply(instance);
			else return super.getExactFloat(ioPool, instance);
		}
		
		@Override
		protected void setExactBoolean(VarPool<CTyp> ioPool, CTyp instance, boolean value){
			if(setter != null) setter.accept(instance, value);
			else super.setExactBoolean(ioPool, instance, value);
		}
		@Override
		protected boolean getExactBoolean(VarPool<CTyp> ioPool, CTyp instance){
			if(getter != null) return (boolean)getter.apply(instance);
			else return super.getExactBoolean(ioPool, instance);
		}
		
		@Override
		protected void setExactObject(VarPool<CTyp> ioPool, CTyp instance, Object value){
			if(setter != null) setter.accept(instance, value);
			else super.setExactObject(ioPool, instance, value);
		}
		@Override
		protected Object getExactObject(VarPool<CTyp> ioPool, CTyp instance){
			if(getter != null) return getter.apply(instance);
			else return super.getExactObject(ioPool, instance);
		}
	}
	
	public static final class PtrFunc<CTyp extends IOInstance<CTyp>> extends Funct<CTyp>{
		
		public PtrFunc(Struct<CTyp> struct, Field field, Optional<Method> getter, Optional<Method> setter, String name){
			super(struct, field, getter, setter, name, ChunkPointer.class);
		}
		@Override
		public long getLong(VarPool<CTyp> ioPool, CTyp instance){
			var num = (ChunkPointer)get(ioPool, instance);
			if(num == null) fail();
			return num.getValue();
		}
		private void fail(){
			throw new NullPointerException("value in " + getType().getName() + "#" + getName() + " is null but ChunkPointer is a non nullable type");
		}
		@Override
		public void setLong(VarPool<CTyp> ioPool, CTyp instance, long value){
			set(ioPool, instance, ChunkPointer.of(value));
		}
	}
	
	public static final class Ptr<CTyp extends IOInstance<CTyp>> extends UnsafeAccessor<CTyp>{
		
		public Ptr(Struct<CTyp> struct, Field field, String name){
			super(struct, field, name, ChunkPointer.class);
		}
		@Override
		public long getLong(VarPool<CTyp> ioPool, CTyp instance){
			var num = (ChunkPointer)get(ioPool, instance);
			if(num == null) fail();
			return num.getValue();
		}
		private void fail(){
			throw new NullPointerException("value in " + getType().getName() + "#" + getName() + " is null but ChunkPointer is a non nullable type");
		}
		@Override
		public void setLong(VarPool<CTyp> ioPool, CTyp instance, long value){
			set(ioPool, instance, ChunkPointer.of(value));
		}
	}
	
	public static <T extends IOInstance<T>> FieldAccessor<T> make(Struct<T> struct, Field field, Optional<Method> getter, Optional<Method> setter, String name, Type genericType){
		if(genericType == ChunkPointer.class){
			if(getter.isEmpty() && setter.isEmpty()){
				return new Ptr<>(struct, field, name);
			}
			return new PtrFunc<>(struct, field, getter, setter, name);
		}else{
			if(getter.isEmpty() && setter.isEmpty()){
				return new UnsafeAccessor<>(struct, field, name, genericType);
			}
			return new UnsafeAccessor.Funct<>(struct, field, getter, setter, name, genericType);
		}
	}
	
	private final Class<?> declaringClass;
	private final long     fieldOffset;
	
	public UnsafeAccessor(Struct<CTyp> struct, Field field, String name, Type genericType){
		super(struct, name, genericType, IOFieldTools.computeAnnotations(field), Modifier.isFinal(field.getModifiers()));
		declaringClass = field.getDeclaringClass();
		fieldOffset = MyUnsafe.objectFieldOffset(field);
	}
	
	private void checkInstance(CTyp instance){
		if(instance == null){
			throw new NullPointerException();
		}
		declaringClass.cast(instance);
	}
	
	@Override
	protected short getExactShort(VarPool<CTyp> ioPool, CTyp instance){
		checkInstance(instance);
		return UNSAFE.getShort(instance, fieldOffset);
	}
	@Override
	protected void setExactShort(VarPool<CTyp> ioPool, CTyp instance, short value){
		checkInstance(instance);
		checkReadOnlyField();
		UNSAFE.putShort(instance, fieldOffset, value);
	}
	
	@Override
	protected char getExactChar(VarPool<CTyp> ioPool, CTyp instance){
		checkInstance(instance);
		return UNSAFE.getChar(instance, fieldOffset);
	}
	@Override
	protected void setExactChar(VarPool<CTyp> ioPool, CTyp instance, char value){
		checkInstance(instance);
		checkReadOnlyField();
		UNSAFE.putChar(instance, fieldOffset, value);
	}
	
	@Override
	protected long getExactLong(VarPool<CTyp> ioPool, CTyp instance){
		checkInstance(instance);
		return UNSAFE.getLong(instance, fieldOffset);
	}
	@Override
	protected void setExactLong(VarPool<CTyp> ioPool, CTyp instance, long value){
		checkInstance(instance);
		checkReadOnlyField();
		UNSAFE.putLong(instance, fieldOffset, value);
	}
	
	@Override
	protected byte getExactByte(VarPool<CTyp> ioPool, CTyp instance){
		checkInstance(instance);
		return UNSAFE.getByte(instance, fieldOffset);
	}
	@Override
	protected void setExactByte(VarPool<CTyp> ioPool, CTyp instance, byte value){
		checkInstance(instance);
		checkReadOnlyField();
		UNSAFE.putByte(instance, fieldOffset, value);
	}
	
	@Override
	protected int getExactInt(VarPool<CTyp> ioPool, CTyp instance){
		checkInstance(instance);
		return UNSAFE.getInt(instance, fieldOffset);
	}
	@Override
	protected void setExactInt(VarPool<CTyp> ioPool, CTyp instance, int value){
		checkInstance(instance);
		checkReadOnlyField();
		UNSAFE.putInt(instance, fieldOffset, value);
	}
	
	@Override
	protected double getExactDouble(VarPool<CTyp> ioPool, CTyp instance){
		checkInstance(instance);
		return UNSAFE.getDouble(instance, fieldOffset);
	}
	@Override
	protected void setExactDouble(VarPool<CTyp> ioPool, CTyp instance, double value){
		checkInstance(instance);
		checkReadOnlyField();
		UNSAFE.putDouble(instance, fieldOffset, value);
	}
	
	@Override
	protected float getExactFloat(VarPool<CTyp> ioPool, CTyp instance){
		checkInstance(instance);
		return UNSAFE.getFloat(instance, fieldOffset);
	}
	@Override
	protected void setExactFloat(VarPool<CTyp> ioPool, CTyp instance, float value){
		checkInstance(instance);
		checkReadOnlyField();
		UNSAFE.putFloat(instance, fieldOffset, value);
	}
	
	@Override
	protected boolean getExactBoolean(VarPool<CTyp> ioPool, CTyp instance){
		checkInstance(instance);
		return UNSAFE.getBoolean(instance, fieldOffset);
	}
	@Override
	protected void setExactBoolean(VarPool<CTyp> ioPool, CTyp instance, boolean value){
		checkInstance(instance);
		checkReadOnlyField();
		UNSAFE.putBoolean(instance, fieldOffset, value);
	}
	
	@Override
	protected Object getExactObject(VarPool<CTyp> ioPool, CTyp instance){
		checkInstance(instance);
		return UNSAFE.getObject(instance, fieldOffset);
	}
	@Override
	protected void setExactObject(VarPool<CTyp> ioPool, CTyp instance, Object value){
		checkInstance(instance);
		checkReadOnlyField();
		UNSAFE.putObject(instance, fieldOffset, getType().cast(value));
	}
}
