package com.lapissea.cfs.type.field.access;

import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.util.NotNull;

public interface FieldAccessor<CTyp extends IOInstance<CTyp>> extends AnnotatedType, Comparable<FieldAccessor<CTyp>>{
	
	Struct<CTyp> getDeclaringStruct();
	
	@NotNull
	String getName();
	
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
	default void setLong(Struct.Pool<CTyp> ioPool, CTyp instance, long value)      {set(ioPool, instance, value);}
	
	default int getInt(Struct.Pool<CTyp> ioPool, CTyp instance)                    {return (int)get(ioPool, instance);}
	default void setInt(Struct.Pool<CTyp> ioPool, CTyp instance, int value)        {set(ioPool, instance, value);}
	
	default short getShort(Struct.Pool<CTyp> ioPool, CTyp instance)                {return (short)get(ioPool, instance);}
	default void setShort(Struct.Pool<CTyp> ioPool, CTyp instance, short value)    {set(ioPool, instance, value);}
	
	@Override
	default int compareTo(FieldAccessor<CTyp> o){
		return getName().compareTo(o.getName());
	}
	
	
	default void init(IOField<CTyp, ?> field){}
	
	default boolean canBeNull(){
		return true;
	}
}
