package com.lapissea.cfs;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

public class GenericType implements ParameterizedType{
	
	private final Type[]   genericArgs;
	private final Class<?> rawType;
	private final Type     parentType;
	
	public GenericType(Class<?> rawType, Type parentType, Type[] genericArgs){
		this.genericArgs=genericArgs;
		this.rawType=rawType;
		this.parentType=parentType;
	}
	
	@Override
	public Type[] getActualTypeArguments(){
		return genericArgs;
	}
	@Override
	public Class<?> getRawType(){
		return rawType;
	}
	@Override
	public Type getOwnerType(){
		return parentType;
	}
}
