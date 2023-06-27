package com.lapissea.cfs.type.field.access;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.exceptions.MalformedStruct;
import com.lapissea.cfs.internal.Access;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.GetAnnotation;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.VarPool;
import com.lapissea.util.NotNull;
import com.lapissea.util.Nullable;
import com.lapissea.util.UtilL;

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Optional;

public class FunctionalReflectionAccessor<CTyp extends IOInstance<CTyp>> extends AbstractFieldAccessor<CTyp>{
	
	public static class Ptr<CTyp extends IOInstance<CTyp>> extends FunctionalReflectionAccessor<CTyp>{
		
		public Ptr(Struct<CTyp> struct, GetAnnotation annotations, Method getter, Method setter, String name){
			super(struct, annotations, getter, setter, name, ChunkPointer.class);
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
	
	public static <T extends IOInstance<T>> FunctionalReflectionAccessor<T> make(Struct<T> struct, String name, Method getter, Method setter, GetAnnotation annotations, Type type){
		if(type == ChunkPointer.class){
			return new Ptr<>(struct, annotations, getter, setter, name);
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
		this.annotations = annotations;
		this.genericType = genericType;
		this.rawType = Utils.typeToRaw(genericType);
		typeID = TypeFlag.getId(rawType);
		
		if(!Utils.genericInstanceOf(getter.getReturnType(), genericType)){
			throw new MalformedStruct("getter returns " + getter.getReturnType() + " but " + genericType + " is required\n" + getter);
		}
		if(getter.getParameterCount() != 0){
			throw new MalformedStruct("getter must not have arguments\n" + getter);
		}
		
		if(!Utils.genericInstanceOf(setter.getReturnType(), Void.TYPE)){
			throw new MalformedStruct("setter returns " + setter.getReturnType() + " but " + genericType + " is required\n" + setter);
		}
		if(setter.getParameterCount() != 1){
			throw new MalformedStruct("setter must have 1 argument of " + genericType + "\n" + setter);
		}
		if(!Utils.genericInstanceOf(setter.getGenericParameterTypes()[0], genericType)){
			throw new MalformedStruct("setter argument is " + setter.getGenericParameterTypes()[0] + " but " + genericType + " is required\n" + setter);
		}
		
		this.getter = Access.makeMethodHandle(getter);
		this.setter = Access.makeMethodHandle(setter);
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
	public Object get(VarPool<CTyp> ioPool, CTyp instance){
		try{
			return getter.invoke(instance);
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public void set(VarPool<CTyp> ioPool, CTyp instance, Object value){
		try{
			setter.invoke(instance, value);
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public double getDouble(VarPool<CTyp> ioPool, CTyp instance){
		try{
			return (double)getter.invoke(instance);
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public void setDouble(VarPool<CTyp> ioPool, CTyp instance, double value){
		try{
			setter.invoke(instance, value);
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public float getFloat(VarPool<CTyp> ioPool, CTyp instance){
		try{
			return (float)getter.invoke(instance);
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public void setFloat(VarPool<CTyp> ioPool, CTyp instance, float value){
		try{
			setter.invoke(instance, value);
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public byte getByte(VarPool<CTyp> ioPool, CTyp instance){
		try{
			return (byte)getter.invoke(instance);
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public void setByte(VarPool<CTyp> ioPool, CTyp instance, byte value){
		try{
			setter.invoke(instance, value);
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public boolean getBoolean(VarPool<CTyp> ioPool, CTyp instance){
		try{
			return (boolean)getter.invoke(instance);
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public void setBoolean(VarPool<CTyp> ioPool, CTyp instance, boolean value){
		try{
			setter.invoke(instance, value);
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	
	@Override
	public long getLong(VarPool<CTyp> ioPool, CTyp instance){
		try{
			return (long)getter.invoke(instance);
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public void setLong(VarPool<CTyp> ioPool, CTyp instance, long value){
		try{
			setter.invoke(instance, value);
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public int getInt(VarPool<CTyp> ioPool, CTyp instance){
		try{
			return (int)getter.invoke(instance);
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public void setInt(VarPool<CTyp> ioPool, CTyp instance, int value){
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
		return getName() + "(F)";
	}
}
