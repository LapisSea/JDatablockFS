package com.lapissea.cfs.type.field.access;

import com.lapissea.cfs.type.GenericContext;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.util.NotNull;

import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Optional;

public interface FieldAccessor<CTyp extends IOInstance<CTyp>> extends Comparable<FieldAccessor<CTyp>>{
	
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
	
	Type getGenericType(GenericContext genericContext);
	
	default Class<?> getType(){
		var generic=getGenericType(null);
		return (Class<?>)(generic instanceof ParameterizedType p?p.getRawType():generic);
	}
	
	Object get(Struct.Pool<CTyp> ioPool, CTyp instance);
	void set(Struct.Pool<CTyp> ioPool, CTyp instance, Object value);
	
	default double getDouble(Struct.Pool<CTyp> ioPool, CTyp instance)              {return (double)get(ioPool, instance);}
	default void setDouble(Struct.Pool<CTyp> ioPool, CTyp instance, double value)  {set(ioPool, instance, value);}
	
	default float getFloat(Struct.Pool<CTyp> ioPool, CTyp instance)                {return (float)get(ioPool, instance);}
	default void setFloat(Struct.Pool<CTyp> ioPool, CTyp instance, float value)    {set(ioPool, instance, value);}
	
	default byte getByte(Struct.Pool<CTyp> ioPool, CTyp instance)                  {return (byte)get(ioPool, instance);}
	default void setByte(Struct.Pool<CTyp> ioPool, CTyp instance, byte value)      {set(ioPool, instance, value);}
	
	default boolean getBoolean(Struct.Pool<CTyp> ioPool, CTyp instance)            {return (boolean)get(ioPool, instance);}
	default void setBoolean(Struct.Pool<CTyp> ioPool, CTyp instance, boolean value){set(ioPool, instance, value);}
	
	default long getLong(Struct.Pool<CTyp> ioPool, CTyp instance)                  {return (long)get(ioPool, instance);}
	default void setLong(CTyp instance, long value, Struct.Pool<CTyp> ioPool)      {set(ioPool, instance, value);}
	
	default int getInt(Struct.Pool<CTyp> ioPool, CTyp instance)                    {return (int)get(ioPool, instance);}
	default void setInt(Struct.Pool<CTyp> ioPool, CTyp instance, int value)        {set(ioPool, instance, value);}
	
	default short getShort(Struct.Pool<CTyp> ioPool, CTyp instance)                {return (short)get(ioPool, instance);}
	default void setShort(Struct.Pool<CTyp> ioPool, CTyp instance, short value)    {set(ioPool, instance, value);}
	
	@Override
	default int compareTo(FieldAccessor<CTyp> o){
		return getName().compareTo(o.getName());
	}
	
	
	default void init(IOField<CTyp, ?> field){}
}
