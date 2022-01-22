package com.lapissea.jorth.lang;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public record GenType(String typeName, List<GenType> args, Types type){
	
	public static final GenType VOID  =new GenType("void");
	public static final GenType STRING_BUILDER=new GenType(StringBuilder.class.getTypeName());
	public static final GenType STRING=new GenType(String.class.getTypeName());
	
	private static Types makeTyp(String typeName){
		var lower=typeName.toLowerCase();
		return Arrays.stream(Types.values()).filter(e->e.lower.equals(lower)).findAny().orElse(Types.OBJECT);
	}
	
	public GenType(Class<?> type){
		this(type.getName());
	}
	
	public GenType(String typeName){
		this(typeName, List.of());
	}
	public GenType(String typeName, List<GenType> args){
		this(typeName, List.copyOf(args), makeTyp(typeName));
	}
	
	@Override
	public String toString(){
		return typeName+(args.isEmpty()?"":args.stream().map(GenType::toString).collect(Collectors.joining(", ", "<", ">")));
	}
}
