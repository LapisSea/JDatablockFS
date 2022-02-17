package com.lapissea.jorth.lang;

import com.lapissea.jorth.JorthCompiler;
import com.lapissea.jorth.MalformedJorthException;
import com.lapissea.util.NotImplementedException;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public record GenType(String typeName, int arrayDimensions, List<GenType> args, Types type){
	
	public static final GenType VOID          =new GenType("void");
	public static final GenType STRING_BUILDER=new GenType(StringBuilder.class.getTypeName());
	public static final GenType STRING        =new GenType(String.class.getTypeName());
	
	private static Types makeTyp(String typeName){
		var lower=typeName.toLowerCase();
		return Arrays.stream(Types.values()).filter(e->e.lower.equals(lower)).findAny().orElse(Types.OBJECT);
	}
	
	private static Class<?> fromTyp(Type type){
		if(type instanceof Class<?> c) return c;
		if(type instanceof ParameterizedType p){
			return (Class<?>)p.getRawType();
		}
		if(type instanceof GenericArrayType p){
			return fromTyp(p.getGenericComponentType()).arrayType();
		}
		if(type instanceof TypeVariable p){
			return fromTyp(p.getBounds()[0]);
		}
		throw new NotImplementedException(type+" "+type.getClass().getName());
	}
	
	public GenType(Type type){
		this(fromTyp(type));
	}
	public GenType(Class<?> type){
		this(type.getName());
	}
	
	public GenType(String typeName){
		this(typeName, 0, List.of());
	}
	public GenType(String typeName, int arrayDimensions, List<GenType> args){
		this(typeName, arrayDimensions, List.copyOf(args), makeTyp(typeName));
	}
	
	@Override
	public String toString(){
		return typeName+(args.isEmpty()?"":args.stream().map(GenType::toString).collect(Collectors.joining(", ", "<", ">")))+"[]".repeat(arrayDimensions);
	}
	public boolean instanceOf(JorthCompiler context, GenType popped) throws MalformedJorthException{
		if(popped.equals(this)) return true;
		if(popped.type()!=this.type()) return false;
		
		if(popped.type()==Types.OBJECT){
			if(typeName().equals("java.lang.Object")) return true;
			
			var clazz=context.getClassInfo(popped.typeName);
			return clazz.instanceOf(popped.typeName);
		}
		return false;
	}
	
	public GenType rawType(){
		if(args.isEmpty()) return this;
		return new GenType(typeName, arrayDimensions, List.of());
	}
}
