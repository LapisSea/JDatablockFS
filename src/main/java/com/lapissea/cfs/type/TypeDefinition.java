package com.lapissea.cfs.type;

import com.lapissea.cfs.SyntheticParameterizedType;
import com.lapissea.cfs.Utils;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.util.NotImplementedException;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class TypeDefinition{
	
	public static class Check{
		private final Predicate<Class<?>>             rawCheck;
		private final List<Predicate<TypeDefinition>> argChecks;
		
		public Check(Class<?> rawType, List<Predicate<TypeDefinition>> argChecks){
			this(t->t.equals(rawType), argChecks);
		}
		public Check(Predicate<Class<?>> rawCheck, List<Predicate<TypeDefinition>> argChecks){
			this.rawCheck=rawCheck;
			this.argChecks=List.copyOf(argChecks);
		}
		
		public String findProblem(TypeDefinition type){
			if(!rawCheck.test(type.getTypeClass())) return "Raw type in "+type+" is not valid";
			if(type.argCount()!=argChecks.size()) return "Argument count in "+type+" should be "+argChecks.size()+" but is "+type.argCount();
			int[] badIndex=IntStream.range(0, type.argCount()).filter(i->argChecks.get(i).test(type.arg(i))).toArray();
			return switch(badIndex.length){
				case 0 -> null;
				case 1 -> "Argument "+badIndex[0]+" in "+type+" is not valid";
				default -> "Arguments "+Arrays.toString(badIndex)+" in "+type+" are not valid";
			};
		}
		public boolean isValid(TypeDefinition type){
			return findProblem(type)==null;
		}
		public void ensureValid(TypeDefinition type){
			if(!isValid(type)) throw new IllegalArgumentException(type.toShortString()+" is not valid");
		}
	}
	
	private static final TypeDefinition[] NO_ARGS=new TypeDefinition[0];
	
	public static TypeDefinition of(Class<?> raw, Type... args){
		return of(new SyntheticParameterizedType(raw, args));
	}
	
	public static TypeDefinition of(Type genericType){
		Objects.requireNonNull(genericType);
		genericType=Utils.prottectFromVarType(genericType);
		
		if(genericType instanceof ParameterizedType parm) return new TypeDefinition(
			parm.getRawType().getTypeName(),
			Arrays.stream(parm.getActualTypeArguments()).map(TypeDefinition::of).toArray(TypeDefinition[]::new)
		);
		return new TypeDefinition(genericType.getTypeName(), NO_ARGS);
	}
	
	private Class<?> typeClass;
	@IOValue
	private String   typeName;
	
	@IOValue
	private TypeDefinition[] args;
	
	private Type generic;
	
	public TypeDefinition(){ }
	
	
	public TypeDefinition(String typeName, TypeDefinition... args){
		this.typeName=typeName;
		this.args=args.clone();
	}
	
	public String getTypeName(){
		return typeName;
	}
	
	public Class<?> getTypeClass(){
		if(typeClass==null){
			try{
				typeClass=Class.forName(getTypeName());
			}catch(ClassNotFoundException e){
				throw new NotImplementedException("implement generic ioinstance from stored data");
			}
		}
		return typeClass;
	}
	
	public int argCount(){
		return args.length;
	}
	
	public TypeDefinition arg(int index){
		return args[index];
	}
	public Struct<?> argAsStruct(int index){
		return Struct.ofUnknown(arg(index).getTypeClass());
	}
	
	@Override
	public String toString(){
		return getTypeClass().getName()+(args.length==0?"":Arrays.stream(args).map(TypeDefinition::toString).collect(Collectors.joining(", ", "<", ">")));
	}
	public String toShortString(){
		return getTypeClass().getSimpleName()+(args.length==0?"":Arrays.stream(args).map(TypeDefinition::toShortString).collect(Collectors.joining(", ", "<", ">")));
	}
	public Type generic(){
		if(generic==null) generic=new SyntheticParameterizedType(null, getTypeClass(), Arrays.stream(args).map(TypeDefinition::getTypeClass).toArray(Type[]::new));
		return generic;
	}
}
