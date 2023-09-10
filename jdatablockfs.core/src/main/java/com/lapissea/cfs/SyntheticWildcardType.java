package com.lapissea.cfs;

import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public record SyntheticWildcardType(List<Type> lower, List<Type> upper) implements WildcardType{
	
	public SyntheticWildcardType(List<Type> args, boolean isLower){
		this(isLower? args : List.of(), isLower? List.of() : args);
	}
	public SyntheticWildcardType(List<Type> lower, List<Type> upper){
		this.lower = List.copyOf(lower);
		this.upper = List.copyOf(upper);
		if(!lower.isEmpty() && !upper.isEmpty()){
			throw new IllegalArgumentException("Can not have lower and upper");
		}
	}
	
	@Override
	public Type[] getUpperBounds(){
		return upper.toArray(Type[]::new);
	}
	@Override
	public Type[] getLowerBounds(){
		return lower.toArray(Type[]::new);
	}
	
	@Override
	public String toString(){
		var args = lower.isEmpty()? upper : lower;
		
		if(args.size() == 0 || args.get(0).getTypeName().equals(Object.class.getName())){
			return "?";
		}
		
		return "? " + (!lower.isEmpty()? "super" : "extends") + " " +
		       args.stream().map(Objects::toString).collect(Collectors.joining(" & "));
	}
}
