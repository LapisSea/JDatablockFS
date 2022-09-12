package com.lapissea.cfs.type.field.access;

import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.VarPool;
import com.lapissea.util.NotNull;

public interface FieldAccessor<CTyp extends IOInstance<CTyp>> extends AnnotatedType, Comparable<FieldAccessor<CTyp>>{
	
	int getTypeID();
	
	Struct<CTyp> getDeclaringStruct();
	
	@NotNull
	String getName();
	
	Object get(VarPool<CTyp> ioPool, CTyp instance);
	void set(VarPool<CTyp> ioPool, CTyp instance, Object value);
	
	default double getDouble(VarPool<CTyp> ioPool, CTyp instance)              {return (double)get(ioPool, instance);}
	default void setDouble(VarPool<CTyp> ioPool, CTyp instance, double value)  {set(ioPool, instance, value);}
	
	default float getFloat(VarPool<CTyp> ioPool, CTyp instance)                {return (float)get(ioPool, instance);}
	default void setFloat(VarPool<CTyp> ioPool, CTyp instance, float value)    {set(ioPool, instance, value);}
	
	default byte getByte(VarPool<CTyp> ioPool, CTyp instance)                  {return (byte)get(ioPool, instance);}
	default void setByte(VarPool<CTyp> ioPool, CTyp instance, byte value)      {set(ioPool, instance, value);}
	
	default boolean getBoolean(VarPool<CTyp> ioPool, CTyp instance)            {return (boolean)get(ioPool, instance);}
	default void setBoolean(VarPool<CTyp> ioPool, CTyp instance, boolean value){set(ioPool, instance, value);}
	
	default long getLong(VarPool<CTyp> ioPool, CTyp instance)                  {return (long)get(ioPool, instance);}
	default void setLong(VarPool<CTyp> ioPool, CTyp instance, long value)      {set(ioPool, instance, value);}
	
	default int getInt(VarPool<CTyp> ioPool, CTyp instance)                    {return (int)get(ioPool, instance);}
	default void setInt(VarPool<CTyp> ioPool, CTyp instance, int value)        {set(ioPool, instance, value);}
	
	default short getShort(VarPool<CTyp> ioPool, CTyp instance)                {return (short)get(ioPool, instance);}
	default void setShort(VarPool<CTyp> ioPool, CTyp instance, short value)    {set(ioPool, instance, value);}
	
	default char getChar(VarPool<CTyp> ioPool, CTyp instance)                  {return (Character)get(ioPool, instance);}
	default void setChar(VarPool<CTyp> ioPool, CTyp instance, char value)      {set(ioPool, instance, value);}
	
	@Override
	default int compareTo(FieldAccessor<CTyp> o){
		return getName().compareTo(o.getName());
	}
	
	default boolean canBeNull(){
		return true;
	}
}
