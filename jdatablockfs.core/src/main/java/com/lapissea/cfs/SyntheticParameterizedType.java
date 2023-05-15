package com.lapissea.cfs;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.lapissea.cfs.Utils.extractFromVarType;

public final class SyntheticParameterizedType implements ParameterizedType{
	
	public static Type of(Type ownerType, Class<?> rawType, List<Type> actualTypeArguments){
		if(actualTypeArguments.isEmpty()) return rawType;
		return new SyntheticParameterizedType(ownerType, rawType, actualTypeArguments);
	}
	
	public static Type of(Class<?> rawType, List<Type> actualTypeArguments){
		if(actualTypeArguments.isEmpty()) return rawType;
		return new SyntheticParameterizedType(rawType, actualTypeArguments);
	}
	
	public static SyntheticParameterizedType generalize(Type type){
		return switch(type){
			case ParameterizedType t -> new SyntheticParameterizedType((Class<?>)t.getRawType(), List.of(t.getActualTypeArguments()));
			case TypeVariable t -> new SyntheticParameterizedType((Class<?>)extractFromVarType(t), List.of());
			default -> new SyntheticParameterizedType((Class<?>)type, List.of());
		};
	}
	
	private final List<Type> actualTypeArguments;
	private final Type       ownerType;
	private final Class<?>   rawType;
	
	private SyntheticParameterizedType(Type ownerType, Class<?> rawType, List<Type> actualTypeArguments){
		this.ownerType = ownerType;
		this.actualTypeArguments = List.copyOf(actualTypeArguments);
		this.rawType = rawType;
	}
	
	private SyntheticParameterizedType(Class<?> rawType, List<Type> actualTypeArguments){
		this(null, rawType, actualTypeArguments);
	}
	
	@Override
	public Type[] getActualTypeArguments(){
		return actualTypeArguments.toArray(Type[]::new);
	}
	public List<Type> getActualTypeArgumentsList(){
		return actualTypeArguments;
	}
	
	public Type getActualTypeArgument(int index){
		return actualTypeArguments.get(index);
	}
	public int getActualTypeArgumentCount(){
		return actualTypeArguments.size();
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
		int result = actualTypeArguments.hashCode();
		result = 31*result + (ownerType != null? ownerType.hashCode() : 0);
		result = 31*result + (rawType != null? rawType.hashCode() : 0);
		return result;
	}
	
	@Override
	public boolean equals(Object o){
		if(o instanceof ParameterizedType parmType){
			return Objects.equals(rawType, parmType.getRawType()) &&
			       Objects.equals(ownerType, parmType.getOwnerType()) &&
			       actualTypeArguments.equals(parmType instanceof SyntheticParameterizedType parmTypeDef?
			                                  parmTypeDef.actualTypeArguments :
			                                  Arrays.asList(parmType.getActualTypeArguments()));
		}
		if(o instanceof Type type){
			return ownerType == null &&
			       actualTypeArguments.isEmpty() &&
			       Objects.equals(rawType, type);
		}
		return false;
	}
	@Override
	public String toString(){
		return rawType.getTypeName() + (actualTypeArguments.isEmpty()? "" : actualTypeArguments.stream().map(Type::getTypeName).collect(Collectors.joining(", ", "<", ">")));
	}
}
