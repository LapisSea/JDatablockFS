package com.lapissea.cfs.type;

import com.lapissea.cfs.SyntheticParameterizedType;
import com.lapissea.cfs.Utils;
import com.lapissea.cfs.logging.Log;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.util.NotImplementedException;

import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static com.lapissea.cfs.type.field.annotations.IONullability.Mode.NULLABLE;

public final class TypeLink extends IOInstance.Managed<TypeLink>{
	
	public static class Check{
		public interface ArgCheck{
			interface RawCheck{
				
				RawCheck PRIMITIVE        = of(SupportedPrimitive::isAny, "is not primitive");
				RawCheck INSTANCE         = of(
					type -> IOInstance.isInstance(type) && (!Modifier.isAbstract(type.getModifiers()) || IOInstance.Def.isDefinition(type)),
					type -> {
						if(Modifier.isAbstract(type.getModifiers()) && !IOInstance.Def.isDefinition(type)){
							return type.getSimpleName() + " is an IOInstance but is not an instantiable class";
						}
						return type.getSimpleName() + " is not an IOInstance";
					});
				RawCheck INSTANCE_MANAGED = INSTANCE.and(of(IOInstance::isManaged, "is not a managed IOInstance"));
				
				
				default RawCheck and(RawCheck other){
					var that = this;
					return new RawCheck(){
						@Override
						public boolean check(Class<?> type){
							return that.check(type) && other.check(type);
						}
						@Override
						public String errMsg(Class<?> type){
							if(that.check(type)) return that.errMsg(type);
							return other.errMsg(type);
						}
					};
				}
				static RawCheck of(Predicate<Class<?>> check, String errTypeAnd){
					return of(check, type -> type.getSimpleName() + " " + errTypeAnd);
				}
				static RawCheck of(Predicate<Class<?>> check, Function<Class<?>, String> errorMessage){
					return new RawCheck(){
						@Override
						public boolean check(Class<?> type){
							return check.test(type);
						}
						@Override
						public String errMsg(Class<?> type){
							return errorMessage.apply(type);
						}
					};
				}
				
				boolean check(Class<?> type);
				String errMsg(Class<?> type);
				
				default ArgCheck arg(){
					return (type, db) -> {
						var resolved = type.getTypeClass(db);
						if(check(resolved)){
							return;
						}
						throw new IllegalStateException("Invalid arguments: " + errMsg(resolved));
					};
				}
			}
			
			static ArgCheck rawAny(RawCheck... anyOf){
				var args = List.of(anyOf);
				return (type, db) -> {
					var resolved = type.getTypeClass(db);
					for(var arg : args){
						if(arg.check(resolved)){
							return;
						}
					}
					throw new IllegalStateException("No matching type requirement:\n\t" + args.stream().map(c -> c.errMsg(resolved)).collect(Collectors.joining("\n\t")));
				};
			}
			
			void check(TypeLink type, IOTypeDB db);
		}
		
		private final Consumer<Class<?>> rawCheck;
		private final List<ArgCheck>     argChecks;
		
		public Check(Class<?> rawType, ArgCheck... argChecks){
			this(t -> {
				if(!t.equals(rawType)) throw new ClassCastException(rawType + " is not " + t);
			}, argChecks);
		}
		public Check(Consumer<Class<?>> rawCheck, ArgCheck... argChecks){
			this.rawCheck = rawCheck;
			this.argChecks = List.of(argChecks);
		}
		
		public void ensureValid(TypeLink type, IOTypeDB db){
			try{
				rawCheck.accept(type.getTypeClass(db));
				if(type.argCount() != argChecks.size()) throw new IllegalArgumentException("Argument count in " + type + " should be " + argChecks.size() + " but is " + type.argCount());
				
				var errs = new LinkedList<Throwable>();
				
				for(int i = 0; i<argChecks.size(); i++){
					var typ = type.arg(i);
					var ch  = argChecks.get(i);
					
					try{
						ch.check(typ, db);
					}catch(Throwable e){
						errs.add(new IllegalArgumentException("Argument " + typ + " at " + i + " is not valid!", e));
					}
				}
				
				if(!errs.isEmpty()){
					var err = new IllegalArgumentException("Generic arguments are invalid");
					errs.forEach(err::addSuppressed);
					throw err;
				}
			}catch(Throwable e){
				throw new IllegalArgumentException(type.toShortString() + " is not valid!", e);
			}
		}
	}
	
	private static final TypeLink[] NO_ARGS = new TypeLink[0];
	
	private static Type readType(Iterator<Class<?>> iter){
		var cls = iter.next();
		
		var parms = cls.getTypeParameters();
		if(parms.length == 0){
			return cls;
		}
		
		var args = new Type[parms.length];
		for(int i = 0; i<parms.length; i++){
			args[i] = readType(iter);
		}
		return SyntheticParameterizedType.of(cls, List.of(args));
	}
	
	public static TypeLink ofFlat(Class<?>... args){
		return of(readType(Arrays.asList(args).iterator()));
	}
	
	public static TypeLink of(Class<?> raw, Type... args){
		return of(raw, List.of(args));
	}
	public static TypeLink of(Class<?> raw, List<Type> args){
		return of(SyntheticParameterizedType.of(raw, args));
	}
	
	public static TypeLink of(Type genericType){
		Objects.requireNonNull(genericType);
		var cleanGenericType = Utils.prottectFromVarType(genericType);
		
		if(cleanGenericType instanceof WildcardType wild){
			if(wild.getLowerBounds().length == 0){
				var up = wild.getUpperBounds();
				if(up.length == 1 && up[0] == Object.class){
					return null;
				}
				throw new NotImplementedException(wild.toString());
			}
		}
		
		if(cleanGenericType instanceof ParameterizedType parm){
			var args     = parm.getActualTypeArguments();
			var genLinks = argLinks(genericType, args);
			return new TypeLink(
				(Class<?>)parm.getRawType(),
				genLinks
			);
		}
		return of((Class<?>)cleanGenericType);
	}
	
	private static TypeLink[] argLinks(Type genericType, Type[] args){
		TypeLink[] arr   = new TypeLink[args.length];
		int        count = 0;
		for(Type arg : args){
			if(!arg.equals(genericType)){
				arr[count++] = of(arg);
			}
		}
		if(arr.length == count) return arr;
		return Arrays.copyOf(arr, count);
	}
	
	public static TypeLink of(Class<?> raw){
		return new TypeLink(raw, NO_ARGS);
	}
	
	private static final char                  PRIMITIVE_MARKER = ';';
	private static final Map<String, Class<?>> PRIMITIVE_NAMES  = Map.of(
		PRIMITIVE_MARKER + "B", byte.class,
		PRIMITIVE_MARKER + "S", short.class,
		PRIMITIVE_MARKER + "I", int.class,
		PRIMITIVE_MARKER + "J", long.class,
		PRIMITIVE_MARKER + "F", float.class,
		PRIMITIVE_MARKER + "D", double.class,
		PRIMITIVE_MARKER + "C", char.class,
		PRIMITIVE_MARKER + "Z", boolean.class,
		PRIMITIVE_MARKER + "V", void.class
	);
	
	private static final Map<String, String> PRIMITIVE_CLASS_NAMES_TO_SHORT =
		PRIMITIVE_NAMES.entrySet()
		               .stream()
		               .collect(Collectors.toMap(e -> e.getValue().getName(), Map.Entry::getKey));
	
	private Class<?> typeClass;
	
	@IOValue
	private String     typeName;
	@IOValue
	@IONullability.Elements(NULLABLE)
	private TypeLink[] args;
	
	private Type generic;
	
	public TypeLink(){
		typeName = "";
		args = NO_ARGS;
	}
	
	public TypeLink(Class<?> type, TypeLink... args){
		setTypeName(type.getName());
		this.args = safeArgs(args);
		this.typeClass = type;
	}
	
	private static TypeLink[] safeArgs(TypeLink[] args){
		return (args == null || args.length == 0)? NO_ARGS : args.clone();
	}
	
	private void setTypeName(String typeName){
		this.typeName = PRIMITIVE_CLASS_NAMES_TO_SHORT.getOrDefault(typeName, typeName);
	}
	
	public String getTypeName(){
		if(isPrimitive()){
			return PRIMITIVE_NAMES.get(typeName).getName();
		}
		return typeName;
	}
	
	public Class<?> getTypeClass(IOTypeDB db){
		var c = typeClass;
		if(c != null) return c;
		
		var loaded = Objects.requireNonNull(loadClass(db));
		typeClass = loaded;
		return loaded;
	}
	
	private Class<?> loadClass(IOTypeDB db){
		if(isPrimitive()){
			return PRIMITIVE_NAMES.get(typeName);
		}
		var name = getTypeName();
		try{
			return Class.forName(name);
		}catch(ClassNotFoundException e){
			if(db == null){
				throw new RuntimeException(name + " was unable to be resolved and there is no db provided");
			}
			Log.trace("Loading template: {}#yellow", name);
			try{
				return Class.forName(name, true, db.getTemplateLoader());
			}catch(ClassNotFoundException ex){
				throw new RuntimeException(ex);
			}
		}
	}
	
	public boolean isPrimitive(){
		return typeName.length() == 2 && typeName.charAt(0) == PRIMITIVE_MARKER;
	}
	
	public int argCount(){
		return args.length;
	}
	
	public TypeLink arg(int index){
		return args[index];
	}
	
	public Type genericArg(int index, IOTypeDB db){
		return args[index].generic(db);
	}
	
	public TypeLink[] argsCopy(){
		return Arrays.stream(args).map(IOInstance::clone).toArray(TypeLink[]::new);
	}
	public Type[] genericArgsCopy(IOTypeDB db){
		Type[] res = new Type[args.length];
		for(int i = 0; i<args.length; i++){
			res[i] = args[i].generic(db);
		}
		return res;
	}
	
	@Override
	public String toString(){
		String argStr;
		if(args.length == 0) argStr = "";
		else argStr = Arrays.stream(args).map(Objects::toString).collect(Collectors.joining(", ", "<", ">"));
		
		return Utils.classNameToHuman(getTypeName(), false) + argStr;
	}
	
	@Override
	public String toShortString(){
		String argStr;
		if(args.length == 0) argStr = "";
		else argStr = Arrays.stream(args).map(t -> t == null? "null" : t.toShortString()).collect(Collectors.joining(", ", "<", ">"));
		
		return Utils.classNameToHuman(getTypeName(), true) + argStr;
	}
	public Type generic(IOTypeDB db){
		if(generic == null){
			Type[] tArgs = new Type[args.length];
			for(int i = 0; i<args.length; i++){
				tArgs[i] = args[i].getTypeClass(db);
			}
			generic = SyntheticParameterizedType.of(getTypeClass(db), List.of(tArgs));
		}
		return generic;
	}
	
	@Override
	public boolean equals(Object o){
		return this == o ||
		       o instanceof TypeLink that &&
		       getTypeName().equals(that.getTypeName()) &&
		       argsEqual(args, that.args);
	}
	
	private static boolean argsEqual(TypeLink[] a, TypeLink[] b){
		if(a.length != b.length){
			if(a.length != 0 && b.length != 0) return false;
			var obj = TypeLink.of(Object.class);
			for(var arg : a.length != 0? a : b){
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
		int result = getTypeName().hashCode();
		result = 31*result + Arrays.hashCode(args);
		return result;
	}
	
	public TypeLink withArgs(TypeLink... args){
		var l = new TypeLink();
		
		l.typeName = typeName;
		l.typeClass = typeClass;
		l.args = safeArgs(args);
		
		return l;
	}
}
