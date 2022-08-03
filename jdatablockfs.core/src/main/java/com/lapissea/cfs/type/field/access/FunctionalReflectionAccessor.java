package com.lapissea.cfs.type.field.access;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.exceptions.MalformedStructLayout;
import com.lapissea.cfs.internal.Access;
import com.lapissea.cfs.objects.INumber;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.GetAnnotation;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.util.NotNull;
import com.lapissea.util.Nullable;
import com.lapissea.util.UtilL;

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Optional;
import java.util.function.LongFunction;

public class FunctionalReflectionAccessor<CTyp extends IOInstance<CTyp>> extends AbstractFieldAccessor<CTyp>{
	
	public static class Num<CTyp extends IOInstance<CTyp>> extends FunctionalReflectionAccessor<CTyp>{
		
		private final LongFunction<INumber> constructor;
		
		public Num(Struct<CTyp> struct, GetAnnotation annotations, Method getter, Method setter, String name, Type genericType){
			super(struct, annotations, getter, setter, name, genericType);
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
		public void setLong(Struct.Pool<CTyp> ioPool, CTyp instance, long value){
			set(ioPool, instance, constructor.apply(value));
		}
	}
	
	public static <T extends IOInstance<T>> FunctionalReflectionAccessor<T> make(Struct<T> struct, String name, Method getter, Method setter, GetAnnotation annotations, Type type){
		if(UtilL.instanceOf(Utils.typeToRaw(type), INumber.class)){
			return new FunctionalReflectionAccessor.Num<>(struct, annotations, getter, setter, name, type);
		}else{
			return new FunctionalReflectionAccessor<>(struct, annotations, getter, setter, name, type);
		}
	}
	
	private final Type     genericType;
	private final Class<?> rawType;
	private final int      typeID;
	
	private final MethodHandle getter;
	private final MethodHandle setter;
	
	private final GetAnnotation annotations;
	
	public FunctionalReflectionAccessor(Struct<CTyp> struct, GetAnnotation annotations, Method getter, Method setter, String name, Type genericType){
		super(struct, name);
		this.annotations=annotations;
		this.genericType=genericType;
		this.rawType=Utils.typeToRaw(genericType);
		typeID=TypeFlag.getId(rawType);
		
		if(!Utils.genericInstanceOf(getter.getReturnType(), genericType)){
			throw new MalformedStructLayout("getter returns "+getter.getReturnType()+" but "+genericType+" is required\n"+getter);
		}
		if(getter.getParameterCount()!=0){
			throw new MalformedStructLayout("getter must not have arguments\n"+getter);
		}
		
		if(!Utils.genericInstanceOf(setter.getReturnType(), Void.TYPE)){
			throw new MalformedStructLayout("setter returns "+setter.getReturnType()+" but "+genericType+" is required\n"+setter);
		}
		if(setter.getParameterCount()!=1){
			throw new MalformedStructLayout("setter must have 1 argument of "+genericType+"\n"+setter);
		}
		if(!Utils.genericInstanceOf(setter.getGenericParameterTypes()[0], genericType)){
			throw new MalformedStructLayout("setter argument is "+setter.getGenericParameterTypes()[0]+" but "+genericType+" is required\n"+setter);
		}
		
		this.getter=Access.makeMethodHandle(getter);
		this.setter=Access.makeMethodHandle(setter);
	}
	
	@NotNull
	@Nullable
	@Override
	public <T extends Annotation> Optional<T> getAnnotation(Class<T> annotationClass){
		return Optional.ofNullable(annotations.get(annotationClass));
	}
	
	
	@Override
	public Class<?> getType(){
		return rawType;
	}
	@Override
	public int getTypeID(){
		return typeID;
	}
	
	@Override
	public Type getGenericType(GenericContext genericContext){
		return genericType;
	}
	
	@Override
	public Object get(Struct.Pool<CTyp> ioPool, CTyp instance){
		try{
			return getter.invoke(instance);
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public void set(Struct.Pool<CTyp> ioPool, CTyp instance, Object value){
		try{
			setter.invoke(instance, value);
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public double getDouble(Struct.Pool<CTyp> ioPool, CTyp instance){
		try{
			return (double)getter.invoke(instance);
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public void setDouble(Struct.Pool<CTyp> ioPool, CTyp instance, double value){
		try{
			setter.invoke(instance, value);
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public float getFloat(Struct.Pool<CTyp> ioPool, CTyp instance){
		try{
			return (float)getter.invoke(instance);
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public void setFloat(Struct.Pool<CTyp> ioPool, CTyp instance, float value){
		try{
			setter.invoke(instance, value);
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public byte getByte(Struct.Pool<CTyp> ioPool, CTyp instance){
		try{
			return (byte)getter.invoke(instance);
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public void setByte(Struct.Pool<CTyp> ioPool, CTyp instance, byte value){
		try{
			setter.invoke(instance, value);
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public boolean getBoolean(Struct.Pool<CTyp> ioPool, CTyp instance){
		try{
			return (boolean)getter.invoke(instance);
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public void setBoolean(Struct.Pool<CTyp> ioPool, CTyp instance, boolean value){
		try{
			setter.invoke(instance, value);
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	
	@Override
	public long getLong(Struct.Pool<CTyp> ioPool, CTyp instance){
		try{
			return (long)getter.invoke(instance);
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public void setLong(Struct.Pool<CTyp> ioPool, CTyp instance, long value){
		try{
			setter.invoke(instance, value);
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public int getInt(Struct.Pool<CTyp> ioPool, CTyp instance){
		try{
			return (int)getter.invoke(instance);
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public void setInt(Struct.Pool<CTyp> ioPool, CTyp instance, int value){
		try{
			setter.invoke(instance, value);
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public boolean canBeNull(){
		return !rawType.isPrimitive();
	}
	
	@Override
	protected String strName(){
		return getName()+"(F)";
	}
}
