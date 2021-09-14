package com.lapissea.cfs.type.field.access;

import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.util.NotNull;

import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Optional;

public interface IFieldAccessor<CTyp extends IOInstance<CTyp>> extends Comparable<IFieldAccessor<CTyp>>{
	
	@NotNull
	default <T extends Annotation> Optional<T> getAnnotation(Class<T> annotationClass){
		return Optional.empty();
	}
	default boolean hasAnnotation(Class<? extends Annotation> annotationClass){
		return getAnnotation(annotationClass).isPresent();
	}
	
	Struct<CTyp> getDeclaringStruct();
	
	@NotNull
	String getName();
	
	Type getGenericType();
	
	default Class<?> getType(){
		var generic=getGenericType();
		return (Class<?>)(generic instanceof ParameterizedType p?p.getRawType():generic);
	}
	
	Object get(CTyp instance);
	void set(CTyp instance, Object value);
	
	default double getDouble(CTyp instance)              {return (double)get(instance);}
	default void setDouble(CTyp instance, double value)  {set(instance, value);}
	
	default float getFloat(CTyp instance)                {return (float)get(instance);}
	default void setFloat(CTyp instance, float value)    {set(instance, value);}
	
	default byte getByte(CTyp instance)                  {return (byte)get(instance);}
	default void setByte(CTyp instance, byte value)      {set(instance, value);}
	
	default boolean getBoolean(CTyp instance)            {return (boolean)get(instance);}
	default void setBoolean(CTyp instance, boolean value){set(instance, value);}
	
	default long getLong(CTyp instance)                  {return (long)get(instance);}
	default void setLong(CTyp instance, long value)      {set(instance, value);}
	
	default int getInt(CTyp instance)                    {return (int)get(instance);}
	default void setInt(CTyp instance, int value)        {set(instance, value);}
	
	@Override
	default int compareTo(IFieldAccessor<CTyp> o){
		return getName().compareTo(o.getName());
	}
	
	
	default void init(IOField<CTyp, ?> field){}
}
