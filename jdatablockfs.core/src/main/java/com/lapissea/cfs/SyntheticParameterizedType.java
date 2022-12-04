package com.lapissea.cfs;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.lapissea.cfs.Utils.extractFromVarType;

public final class SyntheticParameterizedType implements ParameterizedType{
	
	public static Type of(Type ownerType, Class<?> rawType, Type[] actualTypeArguments){
		if(actualTypeArguments.length == 0) return rawType;
		return new SyntheticParameterizedType(ownerType, rawType, actualTypeArguments);
	}
	
	public static Type of(Class<?> rawType, Type[] actualTypeArguments){
		if(actualTypeArguments.length == 0) return rawType;
		return new SyntheticParameterizedType(rawType, actualTypeArguments);
	}
	
	public static SyntheticParameterizedType generalize(Type type){
		return switch(type){
			case ParameterizedType t -> new SyntheticParameterizedType((Class<?>)t.getRawType(), t.getActualTypeArguments());
			case TypeVariable t -> new SyntheticParameterizedType((Class<?>)extractFromVarType(t));
			default -> new SyntheticParameterizedType((Class<?>)type);
		};
	}
	
	private final Type[]   actualTypeArguments;
	private final Type     ownerType;
	private final Class<?> rawType;
	
	private SyntheticParameterizedType(Type ownerType, Class<?> rawType, Type... actualTypeArguments){
		this.ownerType = ownerType;
		this.actualTypeArguments = actualTypeArguments.clone();
		this.rawType = rawType;
	}
	
	private SyntheticParameterizedType(Class<?> rawType, Type... actualTypeArguments){
		this(null, rawType, actualTypeArguments);
	}
	
	@Override
	public Type[] getActualTypeArguments(){
		return actualTypeArguments.clone();
	}
	
	public Type getActualTypeArgument(int index){
		return actualTypeArguments[index];
	}
	public int getActualTypeArgumentCount(){
		return actualTypeArguments.length;
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
		int result = Arrays.hashCode(actualTypeArguments);
		result = 31*result + (ownerType != null? ownerType.hashCode() : 0);
		result = 31*result + (rawType != null? rawType.hashCode() : 0);
		return result;
	}
	
	@Override
	public boolean equals(Object o){
		if(o instanceof ParameterizedType parmType){
			return Objects.equals(rawType, parmType.getRawType()) &&
			       Objects.equals(ownerType, parmType.getOwnerType()) &&
			       Arrays.equals(actualTypeArguments, parmType instanceof SyntheticParameterizedType parmTypeDef?
			                                          parmTypeDef.actualTypeArguments :
			                                          parmType.getActualTypeArguments());
		}
		if(o instanceof Type type){
			return ownerType == null &&
			       actualTypeArguments.length == 0 &&
			       Objects.equals(rawType, type);
		}
		return false;
	}
	@Override
	public String toString(){
		return rawType.getTypeName() + (actualTypeArguments.length == 0? "" : Arrays.stream(actualTypeArguments).map(Type::getTypeName).collect(Collectors.joining(", ", "<", ">")));
	}
}
