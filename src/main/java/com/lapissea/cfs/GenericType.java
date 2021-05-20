package com.lapissea.cfs;

import com.lapissea.util.TextUtil;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Collectors;

public class GenericType implements ParameterizedType{
	
	private final Class<?> rawType;
	private final Type[]   genericArgs;
	private final Type     parentType;
	
	public GenericType(Class<?> rawType, Type parentType, Type[] genericArgs){
		this.rawType=Objects.requireNonNull(rawType);
		this.genericArgs=genericArgs;
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
	
	
	@Override
	public String toString(){ return toString(false); }
	public String toShortString(){ return toString(true); }
	
	private String toString(boolean smal){
		StringBuilder result=new StringBuilder();
		if(getOwnerType()!=null) result.append(getOwnerType()).append('.');
		result.append(smal?getRawType().getSimpleName():getRawType().getTypeName());
		
		if(getActualTypeArguments().length>0){
			result.append(Arrays.stream(getActualTypeArguments())
			                    .map(e->smal?TextUtil.toShortString(e):TextUtil.toString(e))
			                    .collect(Collectors.joining(", ", "<", ">")));
		}
		
		return result.toString();
		
	}
}
