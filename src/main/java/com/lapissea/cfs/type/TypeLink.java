package com.lapissea.cfs.type;

import com.lapissea.cfs.SyntheticParameterizedType;
import com.lapissea.cfs.Utils;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.util.NotImplementedException;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.*;
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
	
	private static Type readType(Iterator<Class<?>> iter){
		var cls=iter.next();
		
		var parms=cls.getTypeParameters();
		if(parms.length==0){
			return cls;
		}
		
		var args=new Type[parms.length];
		for(int i=0;i<parms.length;i++){
			args[i]=readType(iter);
		}
		return SyntheticParameterizedType.of(cls, args);
	}
	
	public static TypeLink ofFlat(Class<?>... args){
		return of(readType(Arrays.asList(args).iterator()));
	}
	
	public static TypeLink of(Class<?> raw, Type... args){
		return of(SyntheticParameterizedType.of(raw, args));
	}
	
	public static TypeLink of(Type genericType){
		Objects.requireNonNull(genericType);
		var cleanGenericType=Utils.prottectFromVarType(genericType);
		
		if(cleanGenericType instanceof WildcardType wild){
			if(wild.getLowerBounds().length==0){
				var up=wild.getUpperBounds();
				if(up.length==1&&up[0]==Object.class){
					return null;
				}
				throw new NotImplementedException(wild.toString());
			}
		}
		
		if(cleanGenericType instanceof ParameterizedType parm){
			var args    =parm.getActualTypeArguments();
			var genLinks=Arrays.stream(args).filter(arg->!arg.equals(genericType)).map(TypeLink::of).toArray(TypeLink[]::new);
			return new TypeLink(
				(Class<?>)parm.getRawType(),
				genLinks
			);
		}
		return new TypeLink((Class<?>)cleanGenericType, NO_ARGS);
	}
	
	private Class<?> typeClass;
	
	@IOValue
	private String     typeName="";
	@IOValue
	@IONullability.Elements(IONullability.Mode.NULLABLE)
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
			}catch(ClassNotFoundException ex){
				throw new RuntimeException(ex);
			}
		}
		
	}
	
	public int argCount(){
		return args.length;
	}
	
	public TypeLink arg(int index){
		return args[index];
	}
	public Struct<?> argAsStruct(int index, IOTypeDB db){
		return Struct.ofUnknown(arg(index).getTypeClass(db));
	}
	
	@Override
	public String toString(){
		String argStr;
		if(args.length==0) argStr="";
		else argStr=Arrays.stream(args).map(Objects::toString).collect(Collectors.joining(", ", "<", ">"));
		
		return getTypeName()+argStr;
	}
	@Override
	public String toShortString(){
		String argStr;
		if(args.length==0) argStr="";
		else argStr=Arrays.stream(args).map(t->t==null?"null":t.toShortString()).collect(Collectors.joining(", ", "<", ">"));
		
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
		if(generic==null) generic=SyntheticParameterizedType.of(getTypeClass(db), Arrays.stream(args).map(t->t.getTypeClass(db)).toArray(Type[]::new));
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
