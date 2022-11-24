package com.lapissea.cfs.type.field.access;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.exceptions.MalformedStruct;
import com.lapissea.cfs.internal.Access;
import com.lapissea.cfs.objects.INumber;
import com.lapissea.cfs.type.GenericContext;
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
import java.util.Optional;
import java.util.function.LongFunction;

public sealed class ReflectionAccessor<CTyp extends IOInstance<CTyp>> extends AbstractFieldAccessor<CTyp>{
	
	public static final class Num<CTyp extends IOInstance<CTyp>> extends ReflectionAccessor<CTyp>{
		
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
			return new ReflectionAccessor.Num<>(struct, field, getter, setter, name, genericType);
		}else{
			return new ReflectionAccessor<>(struct, field, getter, setter, name, genericType);
		}
	}
	
	private final Type     genericType;
	private final Class<?> rawType;
	private final int      typeId;
	
	private final Field        field;
	private final MethodHandle getter;
	private final MethodHandle setter;
	
	public ReflectionAccessor(Struct<CTyp> struct, Field field, Optional<Method> getter, Optional<Method> setter, String name, Type genericType){
		super(struct, name);
		this.field=field;
		this.genericType=Utils.prottectFromVarType(genericType);
		this.rawType=Utils.typeToRaw(this.genericType);
		typeId=TypeFlag.getId(rawType);
		
		getter.ifPresent(get->{
			if(!Utils.genericInstanceOf(get.getGenericReturnType(), genericType)){
				throw new MalformedStruct("getter returns "+get.getGenericReturnType()+" but "+genericType+" is required\n"+get);
			}
			if(get.getParameterCount()!=0){
				throw new MalformedStruct("getter must not have arguments\n"+get);
			}
		});
		
		setter.ifPresent(set->{
			if(!Utils.genericInstanceOf(set.getReturnType(), Void.TYPE)){
				throw new MalformedStruct("setter returns "+set.getReturnType()+" but "+genericType+" is required\n"+set);
			}
			if(set.getParameterCount()!=1){
				throw new MalformedStruct("setter must have 1 argument of "+genericType+"\n"+set);
			}
			if(!Utils.genericInstanceOf(set.getGenericParameterTypes()[0], genericType)){
				throw new MalformedStruct("setter argument is "+set.getGenericParameterTypes()[0]+" but "+genericType+" is required\n"+set);
			}
		});
		
		this.getter=getter.map(AbstractPrimitiveAccessor::findParent).map(Access::makeMethodHandle).orElse(null);
		this.setter=setter.map(AbstractPrimitiveAccessor::findParent).map(Access::makeMethodHandle).orElse(null);
	}
	
	@NotNull
	@Nullable
	@Override
	public <T extends Annotation> Optional<T> getAnnotation(Class<T> annotationClass){
		return Optional.ofNullable(field.getAnnotation(annotationClass));
	}
	@Override
	public boolean hasAnnotation(Class<? extends Annotation> annotationClass){
		return field.isAnnotationPresent(annotationClass);
	}
	
	@Override
	public Class<?> getType(){
		return rawType;
	}
	@Override
	public int getTypeID(){
		return typeId;
	}
	
	@Override
	public Type getGenericType(GenericContext genericContext){
		if(genericContext==null){
			return genericType;
		}
		return genericContext.resolveType(genericType);
	}
	
	@Override
	public Object get(VarPool<CTyp> ioPool, CTyp instance){
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
	public void set(VarPool<CTyp> ioPool, CTyp instance, Object value){
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
	public double getDouble(VarPool<CTyp> ioPool, CTyp instance){
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
	public void setDouble(VarPool<CTyp> ioPool, CTyp instance, double value){
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
	public float getFloat(VarPool<CTyp> ioPool, CTyp instance){
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
	public void setFloat(VarPool<CTyp> ioPool, CTyp instance, float value){
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
	public byte getByte(VarPool<CTyp> ioPool, CTyp instance){
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
	public void setByte(VarPool<CTyp> ioPool, CTyp instance, byte value){
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
	public boolean getBoolean(VarPool<CTyp> ioPool, CTyp instance){
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
	public void setBoolean(VarPool<CTyp> ioPool, CTyp instance, boolean value){
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
	public long getLong(VarPool<CTyp> ioPool, CTyp instance){
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
	public void setLong(VarPool<CTyp> ioPool, CTyp instance, long value){
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
	public int getInt(VarPool<CTyp> ioPool, CTyp instance){
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
	public void setInt(VarPool<CTyp> ioPool, CTyp instance, int value){
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
	
	@Override
	public short getShort(VarPool<CTyp> ioPool, CTyp instance){
		try{
			if(getter!=null){
				return (Short)getter.invoke(instance);
			}else{
				return field.getShort(instance);
			}
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public void setShort(VarPool<CTyp> ioPool, CTyp instance, short value){
		try{
			if(setter!=null){
				setter.invoke(instance, value);
			}else{
				field.setShort(instance, value);
			}
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public boolean canBeNull(){
		return !rawType.isPrimitive();
	}
}
