package com.lapissea.cfs.type.field.access;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.exceptions.MalformedStructLayout;
import com.lapissea.cfs.internal.Access;
import com.lapissea.cfs.objects.INumber;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
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
import java.util.function.LongFunction;
import java.util.stream.Collectors;

import static com.lapissea.cfs.internal.MyUnsafe.UNSAFE;

public sealed class UnsafeAccessor<CTyp extends IOInstance<CTyp>> extends AbstractPrimitiveAccessor<CTyp>{
	
	public static sealed class Funct<CTyp extends IOInstance<CTyp>> extends UnsafeAccessor<CTyp>{
		
		private final MethodHandle getter;
		private final MethodHandle setter;
		
		public Funct(Struct<CTyp> struct, Field field, Optional<Method> getter, Optional<Method> setter, String name, Type genericType){
			super(struct, field, name, genericType);
			
			getter.ifPresent(get->{
				if(!Utils.genericInstanceOf(get.getGenericReturnType(), genericType)){
					throw new MalformedStructLayout("getter returns "+get.getGenericReturnType()+" but "+genericType+" is required\n"+get);
				}
				if(get.getParameterCount()!=0){
					throw new MalformedStructLayout("getter must not have arguments\n"+get);
				}
			});
			
			setter.ifPresent(set->{
				if(!Utils.genericInstanceOf(set.getReturnType(), Void.TYPE)){
					throw new MalformedStructLayout("setter returns "+set.getReturnType()+" but "+genericType+" is required\n"+set);
				}
				if(set.getParameterCount()!=1){
					throw new MalformedStructLayout("setter must have 1 argument of "+genericType+"\n"+set);
				}
				if(!Utils.genericInstanceOf(set.getGenericParameterTypes()[0], genericType)){
					throw new MalformedStructLayout("setter argument is "+set.getGenericParameterTypes()[0]+" but "+genericType+" is required\n"+set);
				}
			});
			
			this.getter=getter.map(Access::makeMethodHandle).orElse(null);
			this.setter=setter.map(Access::makeMethodHandle).orElse(null);
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
		public Object get(Struct.Pool<CTyp> ioPool, CTyp instance){
			if(getter!=null) return getter(instance);
			else return super.get(ioPool, instance);
		}
		
		@Override
		public void set(Struct.Pool<CTyp> ioPool, CTyp instance, Object value){
			if(setter!=null) setter(instance, value);
			else super.set(ioPool, instance, value);
		}
		
		@Override
		public double getDouble(Struct.Pool<CTyp> ioPool, CTyp instance){
			if(getter!=null) return (double)getter(instance);
			else return super.getDouble(ioPool, instance);
		}
		
		@Override
		public void setDouble(Struct.Pool<CTyp> ioPool, CTyp instance, double value){
			if(setter!=null) setter(instance, value);
			else super.setDouble(ioPool, instance, value);
		}
		
		@Override
		public float getFloat(Struct.Pool<CTyp> ioPool, CTyp instance){
			if(getter!=null) return (float)getter(instance);
			else return super.getFloat(ioPool, instance);
		}
		
		@Override
		public void setFloat(Struct.Pool<CTyp> ioPool, CTyp instance, float value){
			if(setter!=null) setter(instance, value);
			else super.setFloat(ioPool, instance, value);
		}
		
		@Override
		public byte getByte(Struct.Pool<CTyp> ioPool, CTyp instance){
			if(getter!=null) return (byte)getter(instance);
			else return super.getByte(ioPool, instance);
		}
		
		@Override
		public void setByte(Struct.Pool<CTyp> ioPool, CTyp instance, byte value){
			if(setter!=null) setter(instance, value);
			else super.setByte(ioPool, instance, value);
		}
		
		@Override
		public boolean getBoolean(Struct.Pool<CTyp> ioPool, CTyp instance){
			if(getter!=null) return (boolean)getter(instance);
			else return super.getBoolean(ioPool, instance);
		}
		
		@Override
		public void setBoolean(Struct.Pool<CTyp> ioPool, CTyp instance, boolean value){
			if(setter!=null) setter(instance, value);
			else super.setBoolean(ioPool, instance, value);
		}
		
		
		@Override
		public long getLong(Struct.Pool<CTyp> ioPool, CTyp instance){
			if(getter!=null) return (long)getter(instance);
			else return super.getLong(ioPool, instance);
		}
		
		@Override
		public void setLong(CTyp instance, long value, Struct.Pool<CTyp> ioPool){
			if(setter!=null) setter(instance, value);
			else super.setLong(instance, value, ioPool);
		}
		
		@Override
		public int getInt(Struct.Pool<CTyp> ioPool, CTyp instance){
			if(getter!=null) return (int)getter(instance);
			else return super.getInt(ioPool, instance);
		}
		
		@Override
		public void setInt(Struct.Pool<CTyp> ioPool, CTyp instance, int value){
			if(setter!=null) setter(instance, value);
			else super.setInt(ioPool, instance, value);
		}
		
		@Override
		public short getShort(Struct.Pool<CTyp> ioPool, CTyp instance){
			if(getter!=null) return (Short)getter(instance);
			else return super.getShort(ioPool, instance);
		}
		
		@Override
		public void setShort(Struct.Pool<CTyp> ioPool, CTyp instance, short value){
			if(setter!=null) setter(instance, value);
			else super.setShort(ioPool, instance, value);
		}
	}
	
	public static final class Num<CTyp extends IOInstance<CTyp>> extends UnsafeAccessor.Funct<CTyp>{
		
		private final LongFunction<INumber> constructor;
		
		public Num(Struct<CTyp> struct, Field field, Optional<Method> getter, Optional<Method> setter, String name, Type genericType){
			super(struct, field, getter, setter, name, genericType);
			constructor=Access.findConstructor(getType(), LongFunction.class, long.class);
		}
		@Override
		public long getLong(Struct.Pool<CTyp> ioPool, CTyp instance){
			var num=(INumber)get(ioPool, instance);
			if(num==null){
				throw new NullPointerException("value in "+getType().getName()+"#"+getName()+" is null but INumber is a non nullable type");
			}
			return num.getValue();
		}
		@Override
		public void setLong(CTyp instance, long value, Struct.Pool<CTyp> ioPool){
			set(ioPool, instance, constructor.apply(value));
		}
	}
	
	public static <T extends IOInstance<T>> FieldAccessor<T> make(Struct<T> struct, Field field, Optional<Method> getter, Optional<Method> setter, String name, Type genericType){
		if(genericType instanceof Class<?> c&&UtilL.instanceOf(c, INumber.class)){
			return new UnsafeAccessor.Num<>(struct, field, getter, setter, name, genericType);
		}else{
			if(getter.isEmpty()&&setter.isEmpty()){
				return new UnsafeAccessor<>(struct, field, name, genericType);
			}
			return new UnsafeAccessor.Funct<>(struct, field, getter, setter, name, genericType);
		}
	}
	
	private final long fieldOffset;
	
	private final Map<Class<? extends Annotation>, ? extends Annotation> annotations;
	
	public UnsafeAccessor(Struct<CTyp> struct, Field field, String name, Type genericType){
		super(struct, field, name, genericType);
		fieldOffset=UNSAFE.objectFieldOffset(field);
		annotations=Arrays.stream(field.getAnnotations()).collect(Collectors.toMap(Annotation::annotationType, a->a));
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
	protected void setShort(CTyp instance, short value){
		UNSAFE.putShort(instance, fieldOffset, value);
	}
	@Override
	protected short getShort(CTyp instance){
		return UNSAFE.getShort(instance, fieldOffset);
	}
	
	@Override
	protected long getLong(CTyp instance){
		return UNSAFE.getLong(instance, fieldOffset);
	}
	@Override
	protected void setLong(CTyp instance, long value){
		UNSAFE.putLong(instance, fieldOffset, value);
	}
	
	@Override
	protected byte getByte(CTyp instance){
		return UNSAFE.getByte(instance, fieldOffset);
	}
	@Override
	protected void setByte(CTyp instance, byte value){
		UNSAFE.putByte(instance, fieldOffset, value);
	}
	
	@Override
	protected int getInt(CTyp instance){
		return UNSAFE.getInt(instance, fieldOffset);
	}
	@Override
	protected void setInt(CTyp instance, int value){
		UNSAFE.putInt(instance, fieldOffset, value);
	}
	
	@Override
	protected double getDouble(CTyp instance){
		return UNSAFE.getDouble(instance, fieldOffset);
	}
	@Override
	protected void setDouble(CTyp instance, double value){
		UNSAFE.putDouble(instance, fieldOffset, value);
	}
	
	@Override
	protected void setFloat(CTyp instance, float value){
		UNSAFE.putFloat(instance, fieldOffset, value);
	}
	@Override
	protected float getFloat(CTyp instance){
		return UNSAFE.getFloat(instance, fieldOffset);
	}
	
	@Override
	protected void setBoolean(CTyp instance, boolean value){
		UNSAFE.putBoolean(instance, fieldOffset, value);
	}
	@Override
	protected boolean getBoolean(CTyp instance){
		return UNSAFE.getBoolean(instance, fieldOffset);
	}
	
	@Override
	protected Object getObj(CTyp instance){
		return UNSAFE.getObject(instance, fieldOffset);
	}
	@Override
	protected void setObj(CTyp instance, Object value){
		UNSAFE.putObject(instance, fieldOffset, getType().cast(value));
	}
}
