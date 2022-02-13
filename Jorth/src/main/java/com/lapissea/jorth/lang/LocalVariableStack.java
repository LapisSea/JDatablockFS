package com.lapissea.jorth.lang;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

public class LocalVariableStack{
	
	public record Variable(int accessIndex, String name, GenType type){}
	
	private final Map<String, Variable> stack=new LinkedHashMap<>();
	private       int                   accessCounter;
	
	
	public Variable make(String name, GenType type){
		
		var var=new Variable(accessCounter, name, type);
		accessCounter+=type.type().slotCount;
		
		stack.put(name, var);
		
		return var;
	}
	
	public Optional<Variable> get(String name){
		return Optional.ofNullable(stack.get(name));
	}
	
	public Stream<Variable> stream(){
		return stack.values().stream();
	}
	
	public int size(){
		return stack.size();
	}
	
	public void clear(){
		stack.clear();
		accessCounter=0;
	}
}
