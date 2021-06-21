package com.lapissea.cfs.type.field.access;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.exceptions.MalformedStructLayout;
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
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Optional;
import java.util.function.LongFunction;

public class ReflectionAccessor<CTyp extends IOInstance<CTyp>> implements IFieldAccessor<CTyp>{
	
	public static class INum<CTyp extends IOInstance<CTyp>> extends ReflectionAccessor<CTyp>{
		
		private final LongFunction<INumber> constructor;
		
		public INum(Struct<CTyp> struct, Field field, Optional<Method> getter, Optional<Method> setter, String name, Type genericType){
			super(struct, field, getter, setter, name, genericType);
			constructor=Utils.findConstructor(getType(), LongFunction.class, long.class);
		}
		@Override
		public long getLong(CTyp instance){
			var num=(INumber)get(instance);
			if(num==null){
				throw new NullPointerException("value in "+getType().getName()+"#"+getName()+" is null but INumber is a non nullable type");
			}
			return num.getValue();
		}
		@Override
		public void setLong(CTyp instance, long value){
			set(instance, constructor.apply(value));
		}
	}
	
	private final Struct<CTyp> struct;
	private final String       name;
	
	private final Type     genericType;
	private final Class<?> rawType;
	
	private final Field        field;
	private final MethodHandle getter;
	private final MethodHandle setter;
	
	public ReflectionAccessor(Struct<CTyp> struct, Field field, Optional<Method> getter, Optional<Method> setter, String name, Type genericType){
		this.struct=struct;
		this.field=field;
		this.name=name;
		this.genericType=genericType;
		this.rawType=(Class<?>)(genericType instanceof ParameterizedType p?p.getRawType():genericType);
		
		getter.ifPresent(get->{
			if(!Utils.genericInstanceOf(get.getReturnType(), genericType)){
				throw new MalformedStructLayout("getter returns "+get.getReturnType()+" but "+genericType+" is required\n"+get);
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
		
		this.getter=getter.map(Utils::makeMethodHandle).orElse(null);
		this.setter=setter.map(Utils::makeMethodHandle).orElse(null);
	}
	
	@Override
	public Struct<CTyp> getDeclaringStruct(){
		return struct;
	}
	
	@Nullable
	@Override
	public <T extends Annotation> Optional<T> getAnnotation(Class<T> annotationClass){
		return Optional.ofNullable(field.getAnnotation(annotationClass));
	}
	@Override
	public boolean hasAnnotation(Class<? extends Annotation> annotationClass){
		return field.isAnnotationPresent(annotationClass);
	}
	
	@NotNull
	@Override
	public String getName(){
		return name;
	}
	@Override
	public String toString(){
		return getDeclaringStruct().getType().getName()+"#"+name;
	}
	public String toShortString(){
		return getDeclaringStruct().getType().getSimpleName()+"#"+name;
	}
	
	@Override
	public Class<?> getType(){
		return rawType;
	}
	
	@Override
	public Type getGenericType(){
		return genericType;
	}
	
	@Override
	public Object get(CTyp instance){
		try{
			if(getter!=null){
				return getter.invoke(instance);
			}else{
				return field.get(instance);
			}
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public void set(CTyp instance, Object value){
		try{
			if(setter!=null){
				setter.invoke(instance, value);
			}else{
				field.set(instance, value);
			}
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public double getDouble(CTyp instance){
		try{
			if(getter!=null){
				return (double)getter.invoke(instance);
			}else{
				return field.getDouble(instance);
			}
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public void setDouble(CTyp instance, double value){
		try{
			if(setter!=null){
				setter.invoke(instance, value);
			}else{
				field.setDouble(instance, value);
			}
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public float getFloat(CTyp instance){
		try{
			if(getter!=null){
				return (float)getter.invoke(instance);
			}else{
				return field.getFloat(instance);
			}
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public void setFloat(CTyp instance, float value){
		try{
			if(setter!=null){
				setter.invoke(instance, value);
			}else{
				field.setFloat(instance, value);
			}
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public byte getByte(CTyp instance){
		try{
			if(getter!=null){
				return (byte)getter.invoke(instance);
			}else{
				return field.getByte(instance);
			}
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public void setByte(CTyp instance, byte value){
		try{
			if(setter!=null){
				setter.invoke(instance, value);
			}else{
				field.setByte(instance, value);
			}
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public boolean getBoolean(CTyp instance){
		try{
			if(getter!=null){
				return (boolean)getter.invoke(instance);
			}else{
				return field.getBoolean(instance);
			}
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public void setBoolean(CTyp instance, boolean value){
		try{
			if(setter!=null){
				setter.invoke(instance, value);
			}else{
				field.setBoolean(instance, value);
			}
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	
	@Override
	public long getLong(CTyp instance){
		try{
			if(getter!=null){
				return (long)getter.invoke(instance);
			}else{
				return field.getLong(instance);
			}
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public void setLong(CTyp instance, long value){
		try{
			if(setter!=null){
				setter.invoke(instance, value);
			}else{
				field.setLong(instance, value);
			}
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public int getInt(CTyp instance){
		try{
			if(getter!=null){
				return (int)getter.invoke(instance);
			}else{
				return field.getInt(instance);
			}
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public void setInt(CTyp instance, int value){
		try{
			if(setter!=null){
				setter.invoke(instance, value);
			}else{
				field.setInt(instance, value);
			}
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
}
