package com.lapissea.dfs;

import com.lapissea.util.NotImplementedException;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.lapissea.dfs.Utils.extractBound;

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
			case Class<?> cl -> new SyntheticParameterizedType(cl, List.of());
			case ParameterizedType parm -> new SyntheticParameterizedType(
				parm.getOwnerType(), (Class<?>)parm.getRawType(), List.of(parm.getActualTypeArguments())
			);
			case TypeVariable<?> var -> generalize(extractBound(var));
			case WildcardType wild -> generalize(extractBound(wild));
			case GenericArrayType a -> generalize(a.getGenericComponentType());
			default -> throw new NotImplementedException(type.getClass().getName());
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
