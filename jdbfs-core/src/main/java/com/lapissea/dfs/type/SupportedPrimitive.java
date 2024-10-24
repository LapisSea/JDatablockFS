package com.lapissea.dfs.type;

import com.lapissea.dfs.type.field.SizeDescriptor;
import com.lapissea.dfs.utils.OptionalPP;

import java.lang.reflect.Array;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

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
	
	private static final SupportedPrimitive[]                          UNIVERSE          = values();
	private static final Map<Class<?>, OptionalPP<SupportedPrimitive>> GET_LOOKUP        = lookup(u -> Map.of(u.primitive, u, u.wrapper, u));
	private static final Map<Class<?>, OptionalPP<SupportedPrimitive>> GET_STRICT_LOOKUP = lookup(u -> Map.of(u.primitive, u));
	
	private static Map<Class<?>, OptionalPP<SupportedPrimitive>> lookup(Function<SupportedPrimitive, Map<Class<?>, SupportedPrimitive>> map){
		var res = new HashMap<Class<?>, OptionalPP<SupportedPrimitive>>();
		for(var p : UNIVERSE){
			for(var e : map.apply(p).entrySet()) res.put(e.getKey(), OptionalPP.of(e.getValue()));
		}
		return Map.copyOf(res);
	}
	
	public static OptionalPP<SupportedPrimitive> get(Type type){
		Objects.requireNonNull(type);
		if(type instanceof Class<?> clazz) return get(clazz);
		return OptionalPP.empty();
	}
	public static OptionalPP<SupportedPrimitive> get(Class<?> clazz){
		Objects.requireNonNull(clazz);
		return GET_LOOKUP.getOrDefault(clazz, OptionalPP.empty());
	}
	public static OptionalPP<SupportedPrimitive> getStrict(Type type){
		if(!(type instanceof Class<?> clazz) || !clazz.isPrimitive()) return OptionalPP.empty();
		return getStrict(clazz);
	}
	public static OptionalPP<SupportedPrimitive> getStrict(Class<?> clazz){
		if(!clazz.isPrimitive()) return OptionalPP.empty();
		return GET_STRICT_LOOKUP.getOrDefault(clazz, OptionalPP.empty());
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
