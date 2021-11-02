package com.lapissea.cfs.type.field.access;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.exceptions.MalformedStructLayout;
import com.lapissea.cfs.objects.INumber;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.GetAnnotation;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
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
	
	private final Type     genericType;
	private final Class<?> rawType;
	
	private final MethodHandle getter;
	private final MethodHandle setter;
	
	private final GetAnnotation annotations;
	
	public FunctionalReflectionAccessor(Struct<CTyp> struct, GetAnnotation annotations, Method getter, Method setter, String name, Type genericType){
		super(struct, name);
		this.annotations=annotations;
		this.genericType=genericType;
		this.rawType=Utils.typeToRaw(genericType);
		
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
		
		this.getter=Utils.makeMethodHandle(getter);
		this.setter=Utils.makeMethodHandle(setter);
	}
	
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
	public Type getGenericType(GenericContext genericContext){
		return genericType;
	}
	
	@Override
	public Object get(CTyp instance){
		try{
			return getter.invoke(instance);
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public void set(CTyp instance, Object value){
		try{
			setter.invoke(instance, value);
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public double getDouble(CTyp instance){
		try{
			return (double)getter.invoke(instance);
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public void setDouble(CTyp instance, double value){
		try{
			setter.invoke(instance, value);
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public float getFloat(CTyp instance){
		try{
			return (float)getter.invoke(instance);
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public void setFloat(CTyp instance, float value){
		try{
			setter.invoke(instance, value);
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public byte getByte(CTyp instance){
		try{
			return (byte)getter.invoke(instance);
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public void setByte(CTyp instance, byte value){
		try{
			setter.invoke(instance, value);
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public boolean getBoolean(CTyp instance){
		try{
			return (boolean)getter.invoke(instance);
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public void setBoolean(CTyp instance, boolean value){
		try{
			setter.invoke(instance, value);
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	
	@Override
	public long getLong(CTyp instance){
		try{
			return (long)getter.invoke(instance);
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public void setLong(CTyp instance, long value){
		try{
			setter.invoke(instance, value);
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public int getInt(CTyp instance){
		try{
			return (int)getter.invoke(instance);
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public void setInt(CTyp instance, int value){
		try{
			setter.invoke(instance, value);
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	protected String strName(){
		return getName()+"(F)";
	}
}
