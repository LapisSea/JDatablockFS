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

import static com.lapissea.cfs.type.field.annotations.IONullability.Mode.NULLABLE;

public final class TypeLink extends IOInstance.Managed<TypeLink>{
	
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
		return of((Class<?>)cleanGenericType);
	}
	
	public static TypeLink of(Class<?> raw){
		return new TypeLink(raw, NO_ARGS);
	}
	
	private static final char                  PRIMITIVE_MARKER=';';
	private static final Map<String, Class<?>> PRIMITIVE_NAMES =Map.of(
		PRIMITIVE_MARKER+"B", byte.class,
		PRIMITIVE_MARKER+"S", short.class,
		PRIMITIVE_MARKER+"I", int.class,
		PRIMITIVE_MARKER+"J", long.class,
		PRIMITIVE_MARKER+"F", float.class,
		PRIMITIVE_MARKER+"D", double.class,
		PRIMITIVE_MARKER+"C", char.class,
		PRIMITIVE_MARKER+"Z", boolean.class,
		PRIMITIVE_MARKER+"V", void.class
	);
	
	private Class<?> typeClass;
	
	@IOValue
	private String     typeName;
	@IOValue
	@IONullability.Elements(NULLABLE)
	private TypeLink[] args;
	
	private Type generic;
	
	public TypeLink(){
		typeName="";
		args=NO_ARGS;
	}
	
	public TypeLink(Class<?> type, TypeLink... args){
		setTypeName(type.getName());
		this.args=(args==null||args.length==0)?NO_ARGS:args.clone();
		this.typeClass=type;
	}
	
	private void setTypeName(String typeName){
		this.typeName=PRIMITIVE_NAMES.entrySet().stream().filter(e->e.getValue().getName().equals(typeName)).map(Map.Entry::getKey).findAny().orElse(typeName);
	}
	
	public String getTypeName(){
		if(isPrimitive()){
			return PRIMITIVE_NAMES.get(typeName).getName();
		}
		return typeName;
	}
	
	public Class<?> getTypeClass(IOTypeDB db){
		var c=typeClass;
		if(c!=null) return c;
		
		var loaded=Objects.requireNonNull(loadClass(db));
		typeClass=loaded;
		return loaded;
	}
	
	private Class<?> loadClass(IOTypeDB db){
		if(isPrimitive()){
			return PRIMITIVE_NAMES.get(typeName);
		}
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
	
	public boolean isPrimitive(){
		return typeName.length()==2&&typeName.charAt(0)==PRIMITIVE_MARKER;
	}
	
	public int argCount(){
		return args.length;
	}
	
	public TypeLink arg(int index){
		return args[index];
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
		       argsEqual(args, that.args);
	}
	
	private static boolean argsEqual(TypeLink[] a, TypeLink[] b){
		if(a.length!=b.length){
			if(a.length!=0&&b.length!=0) return false;
			var obj=TypeLink.of(Object.class);
			for(var arg : a.length!=0?a:b){
				if(!arg.equals(obj)){
					return false;
				}
			}
			return true;
		}
		return Arrays.equals(a, b);
	}
	
	@Override
	public int hashCode(){
		int result=getTypeName().hashCode();
		result=31*result+Arrays.hashCode(args);
		return result;
	}
}
