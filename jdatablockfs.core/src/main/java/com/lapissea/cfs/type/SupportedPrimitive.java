package com.lapissea.cfs.type;

import com.lapissea.cfs.OptionalPP;
import com.lapissea.cfs.type.field.SizeDescriptor;

import java.lang.reflect.Array;
import java.lang.reflect.Type;

public enum SupportedPrimitive implements RuntimeType<Object>{
	// @formatter:off
	DOUBLE (double.class,  Double.class,    false, SizeDescriptor.Fixed.of(8)),
	CHAR   (char.class,    Character.class, false, SizeDescriptor.Fixed.of(2)),
	FLOAT  (float.class,   Float.class,     false, SizeDescriptor.Fixed.of(4)),
	LONG   (long.class,    Long.class,      true,  SizeDescriptor.Fixed.of(8)),
	INT    (int.class,     Integer.class,   true,  SizeDescriptor.Fixed.of(4)),
	SHORT  (short.class,   Short.class,     true,  SizeDescriptor.Fixed.of(2)),
	BYTE   (byte.class,    Byte.class,      true,  SizeDescriptor.Fixed.of(1)),
	BOOLEAN(boolean.class, Boolean.class,   false, SizeDescriptor.Fixed.of(WordSpace.BIT, 1));
	// @formatter:on
	
	private static final SupportedPrimitive[] UNIVERSE = values();
	
	public static OptionalPP<SupportedPrimitive> get(Type type){
		if(!(type instanceof Class<?> clazz)) return OptionalPP.empty();
		for(var p : UNIVERSE){
			if(p.is(clazz)){
				return OptionalPP.of(p);
			}
		}
		return OptionalPP.empty();
	}
	public static OptionalPP<SupportedPrimitive> getStrict(Type type){
		if(!(type instanceof Class<?> clazz)) return OptionalPP.empty();
		for(var p : UNIVERSE){
			if(p.isStrict(clazz)){
				return OptionalPP.of(p);
			}
		}
		return OptionalPP.empty();
	}
	
	public static boolean isAnyStrict(Class<?> clazz){
		for(var p : UNIVERSE){
			if(p.isStrict(clazz)) return true;
		}
		return false;
	}
	
	public static boolean isAny(Type type){
		return type instanceof Class<?> c && isAny(c);
	}
	public static boolean isAny(Class<?> clazz){
		for(var p : UNIVERSE){
			if(p.is(clazz)) return true;
		}
		return false;
	}
	
	public final Class<?> primitive;
	public final Class<?> wrapper;
	public final boolean  isInteger;
	
	public final SizeDescriptor.Fixed<?> maxSize;
	
	private final Object defVal;
	
	SupportedPrimitive(Class<?> primitive, Class<?> wrapper, boolean isInteger, SizeDescriptor.Fixed<?> maxSize){
		this.primitive = primitive;
		this.wrapper = wrapper;
		this.maxSize = maxSize;
		this.isInteger = isInteger;
		this.defVal = Array.get(Array.newInstance(primitive, 1), 0);
	}
	
	public boolean isStrict(Class<?> clazz){
		return clazz == primitive;
	}
	public boolean is(Class<?> clazz){
		return clazz == primitive || clazz == wrapper;
	}
	
	
	@Override
	public boolean getCanHavePointers(){
		return false;
	}
	@Override
	public NewObj<Object> emptyConstructor(){
		return this::getDefaultValue;
	}
	public Object getDefaultValue(){
		return defVal;
	}
	@SuppressWarnings("unchecked")
	@Override
	public Class<Object> getType(){
		return (Class<Object>)primitive;
	}
	
	public boolean isInteger(){
		return isInteger;
	}
}
