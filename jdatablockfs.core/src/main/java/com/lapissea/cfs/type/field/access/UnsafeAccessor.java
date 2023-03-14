package com.lapissea.cfs.type.field.access;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.exceptions.MalformedStruct;
import com.lapissea.cfs.internal.Access;
import com.lapissea.cfs.internal.MyUnsafe;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.VarPool;
import com.lapissea.util.NotNull;
import com.lapissea.util.Nullable;
import com.lapissea.util.UtilL;

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.lapissea.cfs.internal.MyUnsafe.UNSAFE;

public sealed class UnsafeAccessor<CTyp extends IOInstance<CTyp>> extends AbstractPrimitiveAccessor<CTyp>{
	
	public static sealed class Funct<CTyp extends IOInstance<CTyp>> extends UnsafeAccessor<CTyp>{
		
		private final MethodHandle getter;
		private final MethodHandle setter;
		
		public Funct(Struct<CTyp> struct, Field field, Optional<Method> getter, Optional<Method> setter, String name, Type genericType){
			super(struct, field, name, genericType);
			
			getter.ifPresent(get -> {
				if(!Utils.genericInstanceOf(get.getGenericReturnType(), genericType)){
					throw new MalformedStruct("getter returns " + get.getGenericReturnType() + " but " + genericType + " is required\n" + get);
				}
				if(get.getParameterCount() != 0){
					throw new MalformedStruct("getter must not have arguments\n" + get);
				}
			});
			
			setter.ifPresent(set -> {
				if(!Utils.genericInstanceOf(set.getReturnType(), Void.TYPE)){
					throw new MalformedStruct("setter returns " + set.getReturnType() + " but " + genericType + " is required\n" + set);
				}
				if(set.getParameterCount() != 1){
					throw new MalformedStruct("setter must have 1 argument of " + genericType + "\n" + set);
				}
				if(!Utils.genericInstanceOf(set.getGenericParameterTypes()[0], genericType)){
					throw new MalformedStruct("setter argument is " + set.getGenericParameterTypes()[0] + " but " + genericType + " is required\n" + set);
				}
			});
			
			this.getter = getter.map(AbstractPrimitiveAccessor::findParent).map(Access::makeMethodHandle).orElse(null);
			this.setter = setter.map(AbstractPrimitiveAccessor::findParent).map(Access::makeMethodHandle).orElse(null);
		}
		
		private Object getter(CTyp instance){
			try{
				return getter.invoke(instance);
			}catch(Throwable e){
				throw UtilL.uncheckedThrow(e);
			}
		}
		private void setter(CTyp instance, Object value){
			try{
				setter.invoke(instance, value);
			}catch(Throwable e){
				throw UtilL.uncheckedThrow(e);
			}
		}
		
		@Override
		public Object get(VarPool<CTyp> ioPool, CTyp instance){
			if(getter != null) return getter(instance);
			else return super.get(ioPool, instance);
		}
		
		@Override
		public void set(VarPool<CTyp> ioPool, CTyp instance, Object value){
			if(setter != null) setter(instance, value);
			else super.set(ioPool, instance, value);
		}
		
		@Override
		public double getDouble(VarPool<CTyp> ioPool, CTyp instance){
			if(getter != null) return (double)getter(instance);
			else return super.getDouble(ioPool, instance);
		}
		
		@Override
		public void setDouble(VarPool<CTyp> ioPool, CTyp instance, double value){
			if(setter != null) setter(instance, value);
			else super.setDouble(ioPool, instance, value);
		}
		
		@Override
		public float getFloat(VarPool<CTyp> ioPool, CTyp instance){
			if(getter != null) return (float)getter(instance);
			else return super.getFloat(ioPool, instance);
		}
		
		@Override
		public void setFloat(VarPool<CTyp> ioPool, CTyp instance, float value){
			if(setter != null) setter(instance, value);
			else super.setFloat(ioPool, instance, value);
		}
		
		@Override
		public byte getByte(VarPool<CTyp> ioPool, CTyp instance){
			if(getter != null) return (byte)getter(instance);
			else return super.getByte(ioPool, instance);
		}
		
		@Override
		public void setByte(VarPool<CTyp> ioPool, CTyp instance, byte value){
			if(setter != null) setter(instance, value);
			else super.setByte(ioPool, instance, value);
		}
		
		@Override
		public boolean getBoolean(VarPool<CTyp> ioPool, CTyp instance){
			if(getter != null) return (boolean)getter(instance);
			else return super.getBoolean(ioPool, instance);
		}
		
		@Override
		public void setBoolean(VarPool<CTyp> ioPool, CTyp instance, boolean value){
			if(setter != null) setter(instance, value);
			else super.setBoolean(ioPool, instance, value);
		}
		
		
		@Override
		public long getLong(VarPool<CTyp> ioPool, CTyp instance){
			if(getter != null) return (long)getter(instance);
			else return super.getLong(ioPool, instance);
		}
		
		@Override
		public void setLong(VarPool<CTyp> ioPool, CTyp instance, long value){
			if(setter != null) setter(instance, value);
			else super.setLong(ioPool, instance, value);
		}
		
		@Override
		public int getInt(VarPool<CTyp> ioPool, CTyp instance){
			if(getter != null) return (int)getter(instance);
			else return super.getInt(ioPool, instance);
		}
		
		@Override
		public void setInt(VarPool<CTyp> ioPool, CTyp instance, int value){
			if(setter != null) setter(instance, value);
			else super.setInt(ioPool, instance, value);
		}
		
		@Override
		public short getShort(VarPool<CTyp> ioPool, CTyp instance){
			if(getter != null) return (Short)getter(instance);
			else return super.getShort(ioPool, instance);
		}
		
		@Override
		public void setShort(VarPool<CTyp> ioPool, CTyp instance, short value){
			if(setter != null) setter(instance, value);
			else super.setShort(ioPool, instance, value);
		}
	}
	
	public static final class Ptr<CTyp extends IOInstance<CTyp>> extends UnsafeAccessor.Funct<CTyp>{
		
		public Ptr(Struct<CTyp> struct, Field field, Optional<Method> getter, Optional<Method> setter, String name){
			super(struct, field, getter, setter, name, ChunkPointer.class);
		}
		@Override
		public long getLong(VarPool<CTyp> ioPool, CTyp instance){
			var num = (ChunkPointer)get(ioPool, instance);
			if(num == null){
				throw new NullPointerException("value in " + getType().getName() + "#" + getName() + " is null but ChunkPointer is a non nullable type");
			}
			return num.getValue();
		}
		@Override
		public void setLong(VarPool<CTyp> ioPool, CTyp instance, long value){
			set(ioPool, instance, ChunkPointer.of(value));
		}
	}
	
	public static <T extends IOInstance<T>> FieldAccessor<T> make(Struct<T> struct, Field field, Optional<Method> getter, Optional<Method> setter, String name, Type genericType){
		if(genericType == ChunkPointer.class){
			return new Ptr<>(struct, field, getter, setter, name);
		}else{
			if(getter.isEmpty() && setter.isEmpty()){
				return new UnsafeAccessor<>(struct, field, name, genericType);
			}
			return new UnsafeAccessor.Funct<>(struct, field, getter, setter, name, genericType);
		}
	}
	
	private final long fieldOffset;
	
	private final Map<Class<? extends Annotation>, ? extends Annotation> annotations;
	
	public UnsafeAccessor(Struct<CTyp> struct, Field field, String name, Type genericType){
		super(struct, name, genericType);
		fieldOffset = MyUnsafe.objectFieldOffset(field);
		annotations = Arrays.stream(field.getAnnotations()).collect(Collectors.toMap(Annotation::annotationType, a -> a));
	}
	
	@NotNull
	@Nullable
	@Override
	public <T extends Annotation> Optional<T> getAnnotation(Class<T> annotationClass){
		return (Optional<T>)Optional.ofNullable(annotations.get(annotationClass));
	}
	@Override
	public boolean hasAnnotation(Class<? extends Annotation> annotationClass){
		return annotations.containsKey(annotationClass);
	}
	
	@Override
	protected void setExactShort(VarPool<CTyp> ioPool, CTyp instance, short value){
		UNSAFE.putShort(instance, fieldOffset, value);
	}
	@Override
	protected short getExactShort(VarPool<CTyp> ioPool, CTyp instance){
		return UNSAFE.getShort(instance, fieldOffset);
	}
	
	@Override
	protected void setExactChar(VarPool<CTyp> ioPool, CTyp instance, char value){
		UNSAFE.putChar(instance, fieldOffset, value);
	}
	@Override
	protected char getExactChar(VarPool<CTyp> ioPool, CTyp instance){
		return UNSAFE.getChar(instance, fieldOffset);
	}
	
	@Override
	protected long getExactLong(VarPool<CTyp> ioPool, CTyp instance){
		return UNSAFE.getLong(instance, fieldOffset);
	}
	@Override
	protected void setExactLong(VarPool<CTyp> ioPool, CTyp instance, long value){
		UNSAFE.putLong(instance, fieldOffset, value);
	}
	
	@Override
	protected byte getExactByte(VarPool<CTyp> ioPool, CTyp instance){
		return UNSAFE.getByte(instance, fieldOffset);
	}
	@Override
	protected void setExactByte(VarPool<CTyp> ioPool, CTyp instance, byte value){
		UNSAFE.putByte(instance, fieldOffset, value);
	}
	
	@Override
	protected int getExactInt(VarPool<CTyp> ioPool, CTyp instance){
		return UNSAFE.getInt(instance, fieldOffset);
	}
	@Override
	protected void setExactInt(VarPool<CTyp> ioPool, CTyp instance, int value){
		UNSAFE.putInt(instance, fieldOffset, value);
	}
	
	@Override
	protected double getExactDouble(VarPool<CTyp> ioPool, CTyp instance){
		return UNSAFE.getDouble(instance, fieldOffset);
	}
	@Override
	protected void setExactDouble(VarPool<CTyp> ioPool, CTyp instance, double value){
		UNSAFE.putDouble(instance, fieldOffset, value);
	}
	
	@Override
	protected void setExactFloat(VarPool<CTyp> ioPool, CTyp instance, float value){
		UNSAFE.putFloat(instance, fieldOffset, value);
	}
	@Override
	protected float getExactFloat(VarPool<CTyp> ioPool, CTyp instance){
		return UNSAFE.getFloat(instance, fieldOffset);
	}
	
	@Override
	protected void setExactBoolean(VarPool<CTyp> ioPool, CTyp instance, boolean value){
		UNSAFE.putBoolean(instance, fieldOffset, value);
	}
	@Override
	protected boolean getExactBoolean(VarPool<CTyp> ioPool, CTyp instance){
		return UNSAFE.getBoolean(instance, fieldOffset);
	}
	
	@Override
	protected Object getExactObject(VarPool<CTyp> ioPool, CTyp instance){
		return UNSAFE.getObject(instance, fieldOffset);
	}
	@Override
	protected void setExactObject(VarPool<CTyp> ioPool, CTyp instance, Object value){
		UNSAFE.putObject(instance, fieldOffset, getType().cast(value));
	}
}
