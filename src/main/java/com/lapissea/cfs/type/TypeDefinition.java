package com.lapissea.cfs.type;

import com.lapissea.cfs.SyntheticParameterizedType;
import com.lapissea.cfs.Utils;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.util.LogUtil;
import com.lapissea.util.NotImplementedException;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class TypeDefinition extends IOInstance<TypeDefinition>{
	
	public static class Check{
		private final Consumer<Class<?>>             rawCheck;
		private final List<Consumer<TypeDefinition>> argChecks;
		
		public Check(Class<?> rawType, List<Consumer<TypeDefinition>> argChecks){
			this(t->{
				if(!t.equals(rawType)) throw new ClassCastException(rawType+" is not "+t);
			}, argChecks);
		}
		public Check(Consumer<Class<?>> rawCheck, List<Consumer<TypeDefinition>> argChecks){
			this.rawCheck=rawCheck;
			this.argChecks=List.copyOf(argChecks);
		}
		
		public void ensureValid(TypeDefinition type){
			try{
				rawCheck.accept(type.getTypeClass());
				if(type.argCount()!=argChecks.size()) throw new IllegalArgumentException("Argument count in "+type+" should be "+argChecks.size()+" but is "+type.argCount());
				
				var errs=new LinkedList<Throwable>();
				
				for(int i=0;i<argChecks.size();i++){
					var typ=type.arg(i);
					var ch =argChecks.get(i);
					
					try{
						ch.accept(typ);
					}catch(Throwable e){
						errs.add(new IllegalArgumentException("Argument "+typ+" at "+i+" is not valid!", e));
					}
				}
				
				if(!errs.isEmpty()){
					var err=new IllegalArgumentException("Generic arguments are invalid");
					errs.forEach(err::addSuppressed);
					throw err;
				}
			}catch(Throwable e){
				throw new IllegalArgumentException(type.toShortString()+" is not valid!", e);
			}
		}
	}
	
	private static final TypeDefinition[] NO_ARGS=new TypeDefinition[0];
	
	public static TypeDefinition of(Class<?> raw, Type... args){
		return of(new SyntheticParameterizedType(raw, args));
	}
	
	public static TypeDefinition of(Type genericType){
		Objects.requireNonNull(genericType);
		var cleanGenericType=Utils.prottectFromVarType(genericType);
		
		if(cleanGenericType instanceof WildcardType wild){
			LogUtil.println(wild.getLowerBounds());
			LogUtil.println(wild.getUpperBounds());
		}
		
		if(cleanGenericType instanceof ParameterizedType parm){
			var args=parm.getActualTypeArguments();
			return new TypeDefinition(
				(Class<?>)parm.getRawType(),
				Arrays.stream(args).filter(arg->!arg.equals(genericType)).map(TypeDefinition::of).toArray(TypeDefinition[]::new)
			);
		}
		return new TypeDefinition((Class<?>)cleanGenericType, NO_ARGS);
	}
	
	private Class<?> typeClass;
	@IOValue
	private String   typeName;
	
	@IOValue
	private TypeDefinition[] args;
	
	private Type generic;
	
	public TypeDefinition(){}
	
	
	public TypeDefinition(Class<?> type, TypeDefinition... args){
		this(type.getName(), args.clone());
		this.typeClass=type;
	}
	
	public TypeDefinition(String typeName, TypeDefinition... args){
		this.typeName=typeName;
		this.args=args.length==0?args:args.clone();
	}
	
	public String getTypeName(){
		return typeName;
	}
	
	public Class<?> getTypeClass(){
		if(typeClass==null){
			try{
				typeClass=Class.forName(getTypeName());
			}catch(ClassNotFoundException e){
				throw new NotImplementedException("implement generic ioinstance from stored data: "+getTypeName());
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
	@Override
	public String toShortString(){
		return getTypeClass().getSimpleName()+(args.length==0?"":Arrays.stream(args).map(TypeDefinition::toShortString).collect(Collectors.joining(", ", "<", ">")));
	}
	public Type generic(){
		if(generic==null) generic=new SyntheticParameterizedType(null, getTypeClass(), Arrays.stream(args).map(TypeDefinition::getTypeClass).toArray(Type[]::new));
		return generic;
	}
	
	@Override
	public boolean equals(Object o){
		return this==o||
		       o instanceof TypeDefinition that&&
		       getTypeName().equals(that.getTypeName())&&
		       Arrays.equals(args, that.args);
	}
	
	@Override
	public int hashCode(){
		int result=getTypeName().hashCode();
		result=31*result+Arrays.hashCode(args);
		return result;
	}
}
