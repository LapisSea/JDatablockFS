package com.lapissea.cfs.type;

import com.lapissea.cfs.type.field.SizeDescriptor;

import java.lang.reflect.Type;
import java.util.Optional;

public enum SupportedPrimitive{
	DOUBLE(double.class, Double.class, new RuntimeType.Lambda<>(double.class, ()->0D), SizeDescriptor.Fixed.of(8)),
	FLOAT(float.class, Float.class, new RuntimeType.Lambda<>(Float.class, ()->0F), SizeDescriptor.Fixed.of(4)),
	LONG(long.class, Long.class, new RuntimeType.Lambda<>(Long.class, ()->0L), SizeDescriptor.Fixed.of(8)),
	INT(int.class, Integer.class, new RuntimeType.Lambda<>(Integer.class, ()->0), SizeDescriptor.Fixed.of(4)),
	SHORT(short.class, Short.class, new RuntimeType.Lambda<>(Short.class, ()->(short)0), SizeDescriptor.Fixed.of(2)),
	BYTE(byte.class, Byte.class, new RuntimeType.Lambda<>(Byte.class, ()->(byte)0), SizeDescriptor.Fixed.of(1)),
	BOOLEAN(boolean.class, Boolean.class, new RuntimeType.Lambda<>(Boolean.class, ()->false), SizeDescriptor.Fixed.of(WordSpace.BIT, 1));
	
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
	
	public final Class<?>       primitive;
	public final Class<?>       wrapper;
	public final RuntimeType<?> runtimeType;
	
	public final SizeDescriptor.Fixed<?> maxSize;
	
	SupportedPrimitive(Class<?> primitive, Class<?> wrapper, RuntimeType<?> runtimeType, SizeDescriptor.Fixed<?> maxSize){
		this.primitive=primitive;
		this.wrapper=wrapper;
		this.runtimeType=runtimeType;
		this.maxSize=maxSize;
	}
	
	public boolean isStrict(Class<?> clazz){
		return clazz==primitive;
	}
	public boolean is(Class<?> clazz){
		return clazz==primitive||clazz==wrapper;
	}
	
	
}
