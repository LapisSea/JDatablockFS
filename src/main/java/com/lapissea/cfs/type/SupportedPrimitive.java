package com.lapissea.cfs.type;

import com.lapissea.cfs.type.field.SizeDescriptor;

import java.lang.reflect.Array;
import java.lang.reflect.Type;
import java.util.Optional;
import java.util.function.Supplier;

public enum SupportedPrimitive implements RuntimeType<Object>{
	DOUBLE(double.class, Double.class, SizeDescriptor.Fixed.of(8)),
	FLOAT(float.class, Float.class, SizeDescriptor.Fixed.of(4)),
	LONG(long.class, Long.class, SizeDescriptor.Fixed.of(8)),
	INT(int.class, Integer.class, SizeDescriptor.Fixed.of(4)),
	SHORT(short.class, Short.class, SizeDescriptor.Fixed.of(2)),
	BYTE(byte.class, Byte.class, SizeDescriptor.Fixed.of(1)),
	BOOLEAN(boolean.class, Boolean.class, SizeDescriptor.Fixed.of(WordSpace.BIT, 1));
	
	private static final SupportedPrimitive[] UNIVERSE=values();
	
	public static Optional<SupportedPrimitive> get(Type type){
		if(!(type instanceof Class<?> clazz)) return Optional.empty();
		for(var p : UNIVERSE){
			if(p.is(clazz)){
				return Optional.of(p);
			}
		}
		return Optional.empty();
	}
	public static Optional<SupportedPrimitive> getStrict(Type type){
		if(!(type instanceof Class<?> clazz)) return Optional.empty();
		for(var p : UNIVERSE){
			if(p.isStrict(clazz)){
				return Optional.of(p);
			}
		}
		return Optional.empty();
	}
	
	public static boolean isAnyStrict(Class<?> clazz){
		for(var p : UNIVERSE){
			if(p.isStrict(clazz)) return true;
		}
		return false;
	}
	
	public static boolean isAny(Type type){
		return type instanceof Class<?> c&&isAny(c);
	}
	public static boolean isAny(Class<?> clazz){
		for(var p : UNIVERSE){
			if(p.is(clazz)) return true;
		}
		return false;
	}
	
	public final Class<?> primitive;
	public final Class<?> wrapper;
	
	public final SizeDescriptor.Fixed<?> maxSize;
	
	private final Object defVal;
	
	SupportedPrimitive(Class<?> primitive, Class<?> wrapper, SizeDescriptor.Fixed<?> maxSize){
		this.primitive=primitive;
		this.wrapper=wrapper;
		this.maxSize=maxSize;
		this.defVal=Array.get(Array.newInstance(primitive, 1), 0);
	}
	
	public boolean isStrict(Class<?> clazz){
		return clazz==primitive;
	}
	public boolean is(Class<?> clazz){
		return clazz==primitive||clazz==wrapper;
	}
	
	
	@Override
	public boolean getCanHavePointers(){
		return false;
	}
	@Override
	public Supplier<Object> requireEmptyConstructor(){
		return ()->defVal;
	}
	@Override
	public Class<Object> getType(){
		return (Class<Object>)primitive;
	}
}
