package com.lapissea.dfs;

import com.lapissea.dfs.utils.Iters;

import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.List;

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
		
		if(args.size() == 0 || args.getFirst().getTypeName().equals(Object.class.getName())){
			return "?";
		}
		
		return "? " + (!lower.isEmpty()? "super" : "extends") + " " + Iters.from(args).joinAsStr(" & ");
	}
}
