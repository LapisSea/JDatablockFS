package com.lapissea.cfs.type;

import com.lapissea.cfs.SyntheticParameterizedType;
import com.lapissea.cfs.Utils;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.util.LogUtil;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public final class TypeLink extends IOInstance<TypeLink>{
	
	public static class Check{
		private final Consumer<Class<?>>       rawCheck;
		private final List<Consumer<TypeLink>> argChecks;
		
		public Check(Class<?> rawType, List<Consumer<TypeLink>> argChecks){
			this(t->{
				if(!t.equals(rawType)) throw new ClassCastException(rawType+" is not "+t);
			}, argChecks);
		}
		public Check(Consumer<Class<?>> rawCheck, List<Consumer<TypeLink>> argChecks){
			this.rawCheck=rawCheck;
			this.argChecks=List.copyOf(argChecks);
		}
		
		public void ensureValid(TypeLink type){
			try{
				rawCheck.accept(type.getTypeClass(null));
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
	
	private static final TypeLink[] NO_ARGS=new TypeLink[0];
	
	public static TypeLink of(Class<?> raw, Type... args){
		return of(new SyntheticParameterizedType(raw, args));
	}
	
	public static TypeLink of(Type genericType){
		Objects.requireNonNull(genericType);
		var cleanGenericType=Utils.prottectFromVarType(genericType);
		
		if(cleanGenericType instanceof WildcardType wild){
			LogUtil.println(wild.getLowerBounds());
			LogUtil.println(wild.getUpperBounds());
		}
		
		if(cleanGenericType instanceof ParameterizedType parm){
			var args=parm.getActualTypeArguments();
			return new TypeLink(
				(Class<?>)parm.getRawType(),
				Arrays.stream(args).filter(arg->!arg.equals(genericType)).map(TypeLink::of).toArray(TypeLink[]::new)
			);
		}
		return new TypeLink((Class<?>)cleanGenericType, NO_ARGS);
	}
	
	private Class<?> typeClass;
	
	@IOValue
	private String     typeName="";
	@IOValue
	private TypeLink[] args    =new TypeLink[0];
	
	private Type generic;
	
	public TypeLink(){}
	
	
	public TypeLink(Class<?> type, TypeLink... args){
		this.typeName=type.getName();
		this.args=args.length==0?args:args.clone();
		
		this.typeClass=type;
	}
	
	public TypeLink(String typeName, TypeLink... args){
		this.typeName=typeName;
		this.args=args.length==0?args:args.clone();
	}
	
	public String getTypeName(){
		return typeName;
	}
	
	public Class<?> getTypeClass(IOTypeDB db){
		if(typeClass==null){
			typeClass=loadClass(db);
		}
		return typeClass;
	}
	
	private Class<?> loadClass(IOTypeDB db){
		var name=getTypeName();
		try{
			return Class.forName(name);
		}catch(ClassNotFoundException e){
			Objects.requireNonNull(db);
			try{
				return Class.forName(name, true, db.getTemplateLoader());
			}catch(Throwable e1){
				e1.printStackTrace();
				System.exit(-1);
				throw new RuntimeException(e1);
			}
		}
		
	}
	
	public int argCount(){
		return args.length;
	}
	
	public TypeLink arg(int index){
		return args[index];
	}
	public Struct<?> argAsStruct(int index){
		return Struct.ofUnknown(arg(index).getTypeClass(null));
	}
	
	@Override
	public String toString(){
		String argStr;
		if(args.length==0) argStr="";
		else argStr=Arrays.stream(args).map(TypeLink::toString).collect(Collectors.joining(", ", "<", ">"));
		
		return getTypeName()+argStr;
	}
	@Override
	public String toShortString(){
		String argStr;
		if(args.length==0) argStr="";
		else argStr=Arrays.stream(args).map(TypeLink::toShortString).collect(Collectors.joining(", ", "<", ">"));
		
		String nam=shortTypeString();
		return nam+argStr;
	}
	private String shortTypeString(){
		var nam =getTypeName();
		var last=nam.lastIndexOf('.');
		if(last!=-1){
			nam=nam.substring(last+1);
		}
		return nam;
	}
	public Type generic(IOTypeDB db){
		if(generic==null) generic=new SyntheticParameterizedType(null, getTypeClass(db), Arrays.stream(args).map(t->t.getTypeClass(db)).toArray(Type[]::new));
		return generic;
	}
	
	@Override
	public boolean equals(Object o){
		return this==o||
		       o instanceof TypeLink that&&
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
