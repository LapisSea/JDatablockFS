package com.lapissea.cfs;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Collectors;

public final class SyntheticParameterizedType implements ParameterizedType{
	private final Type[]   actualTypeArguments;
	private final Type     ownerType;
	private final Class<?> rawType;
	
	public SyntheticParameterizedType(Type ownerType, Class<?> rawType, Type... actualTypeArguments){
		this.ownerType=ownerType;
		this.actualTypeArguments=actualTypeArguments.clone();
		this.rawType=rawType;
	}
	
	public SyntheticParameterizedType(Class<?> rawType, Type... actualTypeArguments){
		this(null, rawType, actualTypeArguments);
	}
	
	@Override
	public Type[] getActualTypeArguments(){
		return actualTypeArguments.clone();
	}
	
	@Override
	public Type getOwnerType(){
		return ownerType;
	}
	
	@Override
	public Class<?> getRawType(){
		return rawType;
	}
	
	@Override
	public int hashCode(){
		int result=Arrays.hashCode(actualTypeArguments);
		result=31*result+(ownerType!=null?ownerType.hashCode():0);
		result=31*result+(rawType!=null?rawType.hashCode():0);
		return result;
	}
	
	@Override
	public boolean equals(Object o){
		if(o instanceof ParameterizedType parmType){
			return Objects.equals(rawType, parmType.getRawType())&&
			       Objects.equals(ownerType, parmType.getOwnerType())&&
			       Arrays.equals(actualTypeArguments, parmType instanceof SyntheticParameterizedType parmTypeDef?
			                                          parmTypeDef.actualTypeArguments:
			                                          parmType.getActualTypeArguments());
		}
		if(o instanceof Type type){
			return ownerType==null&&
			       actualTypeArguments.length==0&&
			       Objects.equals(rawType, type);
		}
		return false;
	}
	@Override
	public String toString(){
		return rawType.getTypeName()+(actualTypeArguments.length==0?"":Arrays.stream(actualTypeArguments).map(Type::getTypeName).collect(Collectors.joining(", ", "<", ">")));
	}
}
