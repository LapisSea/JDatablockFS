package com.lapissea.cfs.type.field.access;

import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.util.NotNull;
import com.lapissea.util.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Type;

public class ReflectionAccessor<CTyp extends IOInstance<CTyp>> implements IFieldAccessor<CTyp>{
	
	private final Struct<CTyp> struct;
	private final Field        field;
	private final String       name;
	
	public ReflectionAccessor(Struct<CTyp> struct, Field field, String name){
		this.struct=struct;
		this.field=field;
		this.name=name;
	}
	
	@Override
	public Struct<CTyp> getStruct(){
		return struct;
	}
	
	@Nullable
	@Override
	public <T extends Annotation> T getAnnotation(Class<T> annotationClass){
		return field.getAnnotation(annotationClass);
	}
	
	@NotNull
	@Override
	public String getName(){
		return name;
	}
	@Override
	public String toString(){
		return getStruct().getType().getName()+"#"+name;
	}
	public String toShortString(){
		return getStruct().getType().getSimpleName()+"#"+name;
	}
	
	@Override
	public Class<?> getType(){
		return field.getType();
	}
	
	@Override
	public Type getGenericType(){
		return field.getGenericType();
	}
	
	@Override
	public Object get(CTyp instance){
		try{
			return field.get(instance);
		}catch(ReflectiveOperationException e){
			throw new RuntimeException(e);
		}
	}
	
	@Override
	public void set(CTyp instance, Object value){
		try{
			field.set(instance, value);
		}catch(ReflectiveOperationException e){
			throw new RuntimeException(e);
		}
	}
	
	@Override
	public double getDouble(CTyp instance){
		try{
			return field.getDouble(instance);
		}catch(ReflectiveOperationException e){
			throw new RuntimeException(e);
		}
	}
	
	@Override
	public void setDouble(CTyp instance, double value){
		try{
			field.setDouble(instance, value);
		}catch(ReflectiveOperationException e){
			throw new RuntimeException(e);
		}
	}
	
	@Override
	public float getFloat(CTyp instance){
		try{
			return field.getFloat(instance);
		}catch(ReflectiveOperationException e){
			throw new RuntimeException(e);
		}
	}
	
	@Override
	public void setFloat(CTyp instance, float value){
		try{
			field.setFloat(instance, value);
		}catch(ReflectiveOperationException e){
			throw new RuntimeException(e);
		}
	}
	
	@Override
	public byte getByte(CTyp instance){
		try{
			return field.getByte(instance);
		}catch(ReflectiveOperationException e){
			throw new RuntimeException(e);
		}
	}
	
	@Override
	public void setByte(CTyp instance, byte value){
		try{
			field.setByte(instance, value);
		}catch(ReflectiveOperationException e){
			throw new RuntimeException(e);
		}
	}
	
	@Override
	public boolean getBoolean(CTyp instance){
		try{
			return field.getBoolean(instance);
		}catch(ReflectiveOperationException e){
			throw new RuntimeException(e);
		}
	}
	
	@Override
	public void setBoolean(CTyp instance, boolean value){
		try{
			field.setBoolean(instance, value);
		}catch(ReflectiveOperationException e){
			throw new RuntimeException(e);
		}
	}
	
	
	@Override
	public long getLong(CTyp instance){
		try{
			return field.getLong(instance);
		}catch(ReflectiveOperationException e){
			throw new RuntimeException(e);
		}
	}
	
	@Override
	public void setLong(CTyp instance, long value){
		try{
			field.setLong(instance, value);
		}catch(ReflectiveOperationException e){
			throw new RuntimeException(e);
		}
	}
	
	@Override
	public int getInt(CTyp instance){
		try{
			return field.getInt(instance);
		}catch(ReflectiveOperationException e){
			throw new RuntimeException(e);
		}
	}
	
	@Override
	public void setInt(CTyp instance, int value){
		try{
			field.setInt(instance, value);
		}catch(ReflectiveOperationException e){
			throw new RuntimeException(e);
		}
	}
}
