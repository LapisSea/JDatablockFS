package com.lapissea.dfs.type.field.access;

import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.field.IOFieldTools;
import com.lapissea.util.UtilL;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;

public final class ReflectionAccessor<CTyp extends IOInstance<CTyp>> extends ExactFieldAccessor<CTyp>{
	
	public static <T extends IOInstance<T>> FieldAccessor<T> make(Struct<T> struct, Field field, Method getter, Method setter, String name, Type genericType){
		return new ReflectionAccessor<>(struct, field, getter, setter, name, genericType);
	}
	
	private final boolean genericTypeHasArgs;
	
	private final Field  field;
	private final Method getter;
	private final Method setter;
	
	private ReflectionAccessor(Struct<CTyp> struct, Field field, Method getter, Method setter, String name, Type genericType){
		super(struct, name, genericType, IOFieldTools.computeAnnotations(field), Modifier.isFinal(field.getModifiers()));
		this.field = field;
		genericTypeHasArgs = IOFieldTools.doesTypeHaveArgs(genericType);
		
		if(getter != null) validateGetter(genericType, getter);
		if(setter != null) validateSetter(genericType, setter);
		
		this.getter = getter != null? ExactFieldAccessor.findParent(getter) : null;
		this.setter = setter != null? ExactFieldAccessor.findParent(setter) : null;
		
		if(this.getter != null) this.getter.setAccessible(true);
		if(this.setter != null) this.setter.setAccessible(true);
		field.setAccessible(true);
	}
	
	@Override
	public boolean genericTypeHasArgs(){
		return genericTypeHasArgs;
	}
	
	@Override
	public Object getExactObject(VarPool<CTyp> ioPool, CTyp instance){
		try{
			if(getter != null){
				return getter.invoke(instance);
			}else{
				return field.get(instance);
			}
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public void setExactObject(VarPool<CTyp> ioPool, CTyp instance, Object value){
		try{
			if(setter != null){
				setter.invoke(instance, value);
			}else{
				checkReadOnlyField();
				field.set(instance, value);
			}
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public double getExactDouble(VarPool<CTyp> ioPool, CTyp instance){
		try{
			if(getter != null){
				return (double)getter.invoke(instance);
			}else{
				return field.getDouble(instance);
			}
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public void setExactDouble(VarPool<CTyp> ioPool, CTyp instance, double value){
		try{
			if(setter != null){
				setter.invoke(instance, value);
			}else{
				checkReadOnlyField();
				field.setDouble(instance, value);
			}
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public float getExactFloat(VarPool<CTyp> ioPool, CTyp instance){
		try{
			if(getter != null){
				return (float)getter.invoke(instance);
			}else{
				return field.getFloat(instance);
			}
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public void setExactFloat(VarPool<CTyp> ioPool, CTyp instance, float value){
		try{
			if(setter != null){
				setter.invoke(instance, value);
			}else{
				checkReadOnlyField();
				field.setFloat(instance, value);
			}
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public byte getExactByte(VarPool<CTyp> ioPool, CTyp instance){
		try{
			if(getter != null){
				return (byte)getter.invoke(instance);
			}else{
				return field.getByte(instance);
			}
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public void setExactByte(VarPool<CTyp> ioPool, CTyp instance, byte value){
		try{
			if(setter != null){
				setter.invoke(instance, value);
			}else{
				checkReadOnlyField();
				field.setByte(instance, value);
			}
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public boolean getExactBoolean(VarPool<CTyp> ioPool, CTyp instance){
		try{
			if(getter != null){
				return (boolean)getter.invoke(instance);
			}else{
				return field.getBoolean(instance);
			}
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public void setExactBoolean(VarPool<CTyp> ioPool, CTyp instance, boolean value){
		try{
			if(setter != null){
				setter.invoke(instance, value);
			}else{
				checkReadOnlyField();
				field.setBoolean(instance, value);
			}
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	
	@Override
	public long getExactLong(VarPool<CTyp> ioPool, CTyp instance){
		try{
			if(getter != null){
				return (long)getter.invoke(instance);
			}else{
				return field.getLong(instance);
			}
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public void setExactLong(VarPool<CTyp> ioPool, CTyp instance, long value){
		try{
			if(setter != null){
				setter.invoke(instance, value);
			}else{
				checkReadOnlyField();
				field.setLong(instance, value);
			}
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public int getExactInt(VarPool<CTyp> ioPool, CTyp instance){
		try{
			if(getter != null){
				return (int)getter.invoke(instance);
			}else{
				return field.getInt(instance);
			}
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public void setExactInt(VarPool<CTyp> ioPool, CTyp instance, int value){
		try{
			if(setter != null){
				setter.invoke(instance, value);
			}else{
				checkReadOnlyField();
				field.setInt(instance, value);
			}
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public short getExactShort(VarPool<CTyp> ioPool, CTyp instance){
		try{
			if(getter != null){
				return (Short)getter.invoke(instance);
			}else{
				return field.getShort(instance);
			}
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	public void setExactShort(VarPool<CTyp> ioPool, CTyp instance, short value){
		try{
			if(setter != null){
				setter.invoke(instance, value);
			}else{
				checkReadOnlyField();
				field.setShort(instance, value);
			}
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	@Override
	protected char getExactChar(VarPool<CTyp> ioPool, CTyp instance){
		try{
			if(getter != null){
				return (Character)getter.invoke(instance);
			}else{
				return field.getChar(instance);
			}
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	@Override
	protected void setExactChar(VarPool<CTyp> ioPool, CTyp instance, char value){
		try{
			if(setter != null){
				setter.invoke(instance, value);
			}else{
				checkReadOnlyField();
				field.setChar(instance, value);
			}
		}catch(Throwable e){
			throw UtilL.uncheckedThrow(e);
		}
	}
}
