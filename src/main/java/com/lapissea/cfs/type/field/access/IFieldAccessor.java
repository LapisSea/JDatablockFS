package com.lapissea.cfs.type.field.access;

import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.util.NotNull;
import com.lapissea.util.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

public interface IFieldAccessor<CTyp extends IOInstance<CTyp>> extends Comparable<IFieldAccessor<CTyp>>{
	Struct<CTyp> getStruct();
	
	@Nullable
	<T extends Annotation> T getAnnotation(Class<T> annotationClass);
	
	@NotNull
	String getName();
	
	Class<?> getType();
	Type getGenericType();
	
	Object get(CTyp instance);
	void set(CTyp instance, Object value);
	
	double getDouble(CTyp instance);
	void setDouble(CTyp instance, double value);
	
	float getFloat(CTyp instance);
	void setFloat(CTyp instance, float value);
	
	byte getByte(CTyp instance);
	void setByte(CTyp instance, byte value);
	
	boolean getBoolean(CTyp instance);
	void setBoolean(CTyp instance, boolean value);
	
	long getLong(CTyp instance);
	void setLong(CTyp instance, long value);
	
	int getInt(CTyp instance);
	void setInt(CTyp instance, int value);
	
	@Override
	default int compareTo(IFieldAccessor<CTyp> o){
		return getName().compareTo(o.getName());
	}
	
	
	default void init(IOField<CTyp, ?> field){}
}
