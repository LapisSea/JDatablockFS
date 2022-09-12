package com.lapissea.cfs.type.field.access;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.exceptions.MalformedStructLayout;
import com.lapissea.cfs.internal.Access;
import com.lapissea.cfs.objects.INumber;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.VarPool;
import com.lapissea.util.NotNull;
import com.lapissea.util.Nullable;
import com.lapissea.util.UtilL;

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongFunction;
import java.util.stream.Collectors;

public sealed class VarHandleAccessor<CTyp extends IOInstance<CTyp>> extends AbstractPrimitiveAccessor<CTyp>{
	
	public static sealed class Funct<CTyp extends IOInstance<CTyp>> extends VarHandleAccessor<CTyp>{
		
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
		public Object get(VarPool<CTyp> ioPool, CTyp instance){
			if(getter!=null) return getter(instance);
			else return super.get(ioPool, instance);
		}
		
		@Override
		public void set(VarPool<CTyp> ioPool, CTyp instance, Object value){
			if(setter!=null) setter(instance, value);
			else super.set(ioPool, instance, value);
		}
		
		@Override
		public double getDouble(VarPool<CTyp> ioPool, CTyp instance){
			if(getter!=null) return (double)getter(instance);
			else return super.getDouble(ioPool, instance);
		}
		
		@Override
		public void setDouble(VarPool<CTyp> ioPool, CTyp instance, double value){
			if(setter!=null) setter(instance, value);
			else super.setDouble(ioPool, instance, value);
		}
		
		@Override
		public float getFloat(VarPool<CTyp> ioPool, CTyp instance){
			if(getter!=null) return (float)getter(instance);
			else return super.getFloat(ioPool, instance);
		}
		
		@Override
		public void setFloat(VarPool<CTyp> ioPool, CTyp instance, float value){
			if(setter!=null) setter(instance, value);
			else super.setFloat(ioPool, instance, value);
		}
		
		@Override
		public byte getByte(VarPool<CTyp> ioPool, CTyp instance){
			if(getter!=null) return (byte)getter(instance);
			else return super.getByte(ioPool, instance);
		}
		
		@Override
		public void setByte(VarPool<CTyp> ioPool, CTyp instance, byte value){
			if(setter!=null) setter(instance, value);
			else super.setByte(ioPool, instance, value);
		}
		
		@Override
		public boolean getBoolean(VarPool<CTyp> ioPool, CTyp instance){
			if(getter!=null) return (boolean)getter(instance);
			else return super.getBoolean(ioPool, instance);
		}
		
		@Override
		public void setBoolean(VarPool<CTyp> ioPool, CTyp instance, boolean value){
			if(setter!=null) setter(instance, value);
			else super.setBoolean(ioPool, instance, value);
		}
		
		
		@Override
		public long getLong(VarPool<CTyp> ioPool, CTyp instance){
			if(getter!=null) return (long)getter(instance);
			else return super.getLong(ioPool, instance);
		}
		
		@Override
		public void setLong(VarPool<CTyp> ioPool, CTyp instance, long value){
			if(setter!=null) setter(instance, value);
			else super.setLong(ioPool, instance, value);
		}
		
		@Override
		public int getInt(VarPool<CTyp> ioPool, CTyp instance){
			if(getter!=null) return (int)getter(instance);
			else return super.getInt(ioPool, instance);
		}
		
		@Override
		public void setInt(VarPool<CTyp> ioPool, CTyp instance, int value){
			if(setter!=null) setter(instance, value);
			else super.setInt(ioPool, instance, value);
		}
		
		@Override
		public short getShort(VarPool<CTyp> ioPool, CTyp instance){
			if(getter!=null) return (Short)getter(instance);
			else return super.getShort(ioPool, instance);
		}
		
		@Override
		public void setShort(VarPool<CTyp> ioPool, CTyp instance, short value){
			if(setter!=null) setter(instance, value);
			else super.setShort(ioPool, instance, value);
		}
	}
	
	public static final class Num<CTyp extends IOInstance<CTyp>> extends VarHandleAccessor.Funct<CTyp>{
		
		private final LongFunction<INumber> constructor;
		
		public Num(Struct<CTyp> struct, Field field, Optional<Method> getter, Optional<Method> setter, String name, Type genericType){
			super(struct, field, getter, setter, name, genericType);
			constructor=Access.findConstructor(getType(), LongFunction.class, long.class);
		}
		@Override
		public long getLong(VarPool<CTyp> ioPool, CTyp instance){
			var num=(INumber)get(ioPool, instance);
			if(num==null){
				throw new NullPointerException("value in "+getType().getName()+"#"+getName()+" is null but INumber is a non nullable type");
			}
			return num.getValue();
		}
		@Override
		public void setLong(VarPool<CTyp> ioPool, CTyp instance, long value){
			set(ioPool, instance, constructor.apply(value));
		}
	}
	
	public static <T extends IOInstance<T>> FieldAccessor<T> make(Struct<T> struct, Field field, Optional<Method> getter, Optional<Method> setter, String name, Type genericType){
		if(genericType instanceof Class<?> c&&UtilL.instanceOf(c, INumber.class)){
			return new VarHandleAccessor.Num<>(struct, field, getter, setter, name, genericType);
		}else{
			if(getter.isEmpty()&&setter.isEmpty()){
				return new VarHandleAccessor<>(struct, field, name, genericType);
			}
			return new VarHandleAccessor.Funct<>(struct, field, getter, setter, name, genericType);
		}
	}
	
	private final VarHandle handle;
	
	private final Map<Class<? extends Annotation>, ? extends Annotation> annotations;
	
	public VarHandleAccessor(Struct<CTyp> struct, Field field, String name, Type genericType){
		super(struct, name, genericType);
		handle=Access.makeVarHandle(field);
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
	protected void setExactShort(VarPool<CTyp> ioPool, CTyp instance, short value){
		handle.set(instance, value);
	}
	@Override
	protected short getExactShort(VarPool<CTyp> ioPool, CTyp instance){
		return (short)handle.get(instance);
	}
	
	@Override
	protected char getExactChar(VarPool<CTyp> ioPool, CTyp instance){
		return (char)handle.get(instance);
	}
	@Override
	protected void setExactChar(VarPool<CTyp> ioPool, CTyp instance, char value){
		handle.set(instance, value);
	}
	
	@Override
	protected long getExactLong(VarPool<CTyp> ioPool, CTyp instance){
		return (long)handle.get(instance);
	}
	@Override
	protected void setExactLong(VarPool<CTyp> ioPool, CTyp instance, long value){
		handle.set(instance, value);
	}
	
	@Override
	protected byte getExactByte(VarPool<CTyp> ioPool, CTyp instance){
		return (byte)handle.get(instance);
	}
	@Override
	protected void setExactByte(VarPool<CTyp> ioPool, CTyp instance, byte value){
		handle.set(instance, value);
	}
	
	@Override
	protected int getExactInt(VarPool<CTyp> ioPool, CTyp instance){
		return (int)handle.get(instance);
	}
	@Override
	protected void setExactInt(VarPool<CTyp> ioPool, CTyp instance, int value){
		handle.set(instance, value);
	}
	
	@Override
	protected double getExactDouble(VarPool<CTyp> ioPool, CTyp instance){
		return (double)handle.get(instance);
	}
	@Override
	protected void setExactDouble(VarPool<CTyp> ioPool, CTyp instance, double value){
		handle.set(instance, value);
	}
	
	@Override
	protected void setExactFloat(VarPool<CTyp> ioPool, CTyp instance, float value){
		handle.set(instance, value);
	}
	@Override
	protected float getExactFloat(VarPool<CTyp> ioPool, CTyp instance){
		return (float)handle.get(instance);
	}
	
	@Override
	protected void setExactBoolean(VarPool<CTyp> ioPool, CTyp instance, boolean value){
		handle.set(instance, value);
	}
	@Override
	protected boolean getExactBoolean(VarPool<CTyp> ioPool, CTyp instance){
		return (boolean)handle.get(instance);
	}
	
	@Override
	protected Object getExactObject(VarPool<CTyp> ioPool, CTyp instance){
		return handle.get(instance);
	}
	@Override
	protected void setExactObject(VarPool<CTyp> ioPool, CTyp instance, Object value){
		handle.set(instance, getType().cast(value));
	}
}
