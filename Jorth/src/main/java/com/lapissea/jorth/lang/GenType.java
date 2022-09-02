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
import java.util.Objects;
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
		this(getName(type), getDims(type), List.of());
	}
	private static int getDims(Class<?> type){
		var t              =type;
		int arrayDimensions=0;
		while(t.isArray()){
			arrayDimensions++;
			t=t.getComponentType();
		}
		return arrayDimensions;
	}
	private static String getName(Class<?> type){
		var t=type;
		while(t.isArray()){
			t=t.getComponentType();
		}
		return t.getName();
	}
	
	public GenType(String typeName){
		this(typeName, 0, List.of());
	}
	public GenType(String typeName, int arrayDimensions, List<GenType> args){
		this(typeName, arrayDimensions, List.copyOf(args), arrayDimensions>0?Types.OBJECT:makeTyp(typeName));
	}
	
	@Override
	public String toString(){
		return typeName+(args.isEmpty()?"":args.stream().map(GenType::toString).collect(Collectors.joining(", ", "<", ">")))+"[]".repeat(arrayDimensions);
	}
	public boolean instanceOf(JorthCompiler context, GenType popped) throws MalformedJorthException{
		if(popped.equals(this)) return true;
		if(popped.type()!=this.type()) return false;
		var isObj=popped.typeName().equals("java.lang.Object");
		if(!isObj&&popped.arrayDimensions()!=this.arrayDimensions()) return false;
		
		if(popped.type()==Types.OBJECT){
			if(typeName().equals("java.lang.Object")) return true;
			if(popped.typeName().equals("java.lang.Object")) return true;
			
			var clazz=context.getClassInfo(typeName);
			return clazz.instanceOf(popped.typeName);
		}
		return false;
	}
	
	public GenType rawType(){
		if(args.isEmpty()) return this;
		return new GenType(typeName, arrayDimensions, List.of());
	}
	
	
	public String asJorthString(){
		if(args.size()==0&&arrayDimensions==0) return typeName;
		StringBuilder sb=new StringBuilder();
		if(args.size()>0){
			sb.append("[");
			for(GenType arg : args){
				sb.append(arg.asJorthString()).append(" ");
			}
			sb.append("] ");
		}
		if(arrayDimensions>0){
			sb.append("array ".repeat(arrayDimensions));
			sb.append(typeName);
			return sb.toString();
		}
		sb.append(typeName);
		return sb.toString();
	}
	
	public GenType elementType(){
		if(arrayDimensions==0) throw new RuntimeException("Not an array: "+this);
		return new GenType(typeName, arrayDimensions-1, args, type);
	}
	
	@Override
	public boolean equals(Object o){
		if(this==o) return true;
		if(!(o instanceof GenType that)) return false;
		
		var thisArgs=this.args;
		var thatArgs=that.args;
		
		if(thisArgs.size()!=thatArgs.size()&&(thisArgs.size()==0||thatArgs.size()==0)){
			thisArgs=thatArgs=List.of();
		}
		
		if(arrayDimensions!=that.arrayDimensions) return false;
		if(!Objects.equals(typeName, that.typeName)) return false;
		if(!Objects.equals(thisArgs, thatArgs)) return false;
		return type==that.type;
	}
	@Override
	public int hashCode(){
		return typeName.hashCode()+arrayDimensions+args.size()*100;
	}
}
