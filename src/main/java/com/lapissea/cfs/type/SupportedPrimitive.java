package com.lapissea.cfs.type;

import com.lapissea.cfs.io.bit.EnumUniverse;

import java.lang.reflect.Type;
import java.util.Optional;

public enum SupportedPrimitive{
	DOUBLE(double.class, Double.class, new RuntimeType.Lambda<>(double.class, ()->0D)),
	FLOAT(float.class, Float.class, new RuntimeType.Lambda<>(Float.class, ()->0F)),
	LONG(long.class, Long.class, new RuntimeType.Lambda<>(Long.class, ()->0L)),
	INT(int.class, Integer.class, new RuntimeType.Lambda<>(Integer.class, ()->0)),
	SHORT(short.class, Short.class, new RuntimeType.Lambda<>(Short.class, ()->(short)0)),
	BYTE(byte.class, Byte.class, new RuntimeType.Lambda<>(Byte.class, ()->(byte)0)),
	BOOLEAN(boolean.class, Boolean.class, new RuntimeType.Lambda<>(Boolean.class, ()->false));
	
	private static final EnumUniverse<SupportedPrimitive> UNIVERSE=EnumUniverse.get(SupportedPrimitive.class);
	
	public static Optional<SupportedPrimitive> get(Type type){
		if(!(type instanceof Class<?> clazz)) return Optional.empty();
		return UNIVERSE.stream().filter(p->p.is(clazz)).findAny();
	}
	public static Optional<SupportedPrimitive> getStrict(Type type){
		if(!(type instanceof Class<?> clazz)) return Optional.empty();
		return UNIVERSE.stream().filter(p->p.isStrict(clazz)).findAny();
	}
	
	public static boolean isAnyStrict(Class<?> clazz){
		for(SupportedPrimitive p : UNIVERSE){
			if(!p.isStrict(clazz)) return false;
		}
		return true;
	}
	
	public static boolean isAny(Type type){
		return type instanceof Class<?> c&&isAny(c);
	}
	public static boolean isAny(Class<?> clazz){
		for(SupportedPrimitive p : UNIVERSE){
			if(!p.is(clazz)) return false;
		}
		return true;
	}
	
	public final Class<?>       primitive;
	public final Class<?>       wrapper;
	public final RuntimeType<?> runtimeType;
	
	SupportedPrimitive(Class<?> primitive, Class<?> wrapper, RuntimeType<?> runtimeType){
		this.primitive=primitive;
		this.wrapper=wrapper;
		this.runtimeType=runtimeType;
	}
	
	public boolean isStrict(Class<?> clazz){
		return clazz==primitive;
	}
	public boolean is(Class<?> clazz){
		return clazz==primitive||clazz==wrapper;
	}
	
	
}
