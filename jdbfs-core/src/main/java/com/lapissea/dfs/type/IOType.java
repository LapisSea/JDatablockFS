package com.lapissea.dfs.type;

import com.lapissea.dfs.SyntheticParameterizedType;
import com.lapissea.dfs.SyntheticWildcardType;
import com.lapissea.dfs.Utils;
import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.type.compilation.TemplateClassLoader;
import com.lapissea.dfs.type.field.IOFieldTools;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.NotImplementedException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.lang.ref.WeakReference;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.lapissea.dfs.type.field.annotations.IONullability.Mode.NULLABLE;

public abstract sealed class IOType extends IOInstance.Managed<IOType>{
	
	public interface RawAndArg{
		default IOType withDefaultArgs(IOType... args){
			var existing = getArgs();
			
			List<IOType> argsNew;
			if(!existing.isEmpty()){
				if(existing.size() != args.length){
					throw new IllegalStateException("Existing and new argument count does not match - " + existing.size() + " != " + args.length);
				}
				argsNew = existing;
			}else{
				argsNew = List.of(args);
			}
			
			return new TypeGeneric(((IOType)this).getRaw(), argsNew);
		}
		IOType withArgs(IOType... args);
		IOType withRaw(Class<?> raw);
		List<IOType> getArgs();
	}
	
	public static final class TypeRaw extends IOType implements RawAndArg{
		
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
		
		private static final int PRIMITIVE_NAMES_MAX_LEN = Iters.values(PRIMITIVE_NAMES)
		                                                        .map(Class::getName).mapToInt(String::length)
		                                                        .max().orElseThrow();
		
		private static final Map<String, String> PRIMITIVE_CLASS_NAMES_TO_SHORT =
			Iters.entries(PRIMITIVE_NAMES).toMap(e -> e.getValue().getName(), Map.Entry::getKey);
		
		public static final TypeRaw OBJ = new TypeRaw(Object.class);
		
		private static final WeakReference<Class<?>> NO_REF = new WeakReference<>(null);
		
		@IOValue
		private final String name;
		
		private WeakReference<Class<?>> typeClassCache = NO_REF;
		
		
		public TypeRaw(Class<?> clazz){
			this(clazz.getName());
			typeClassCache = new WeakReference<>(clazz);
		}
		public TypeRaw(String name){
			String n;
			if(name.length()>PRIMITIVE_NAMES_MAX_LEN) n = name;
			else{
				n = PRIMITIVE_CLASS_NAMES_TO_SHORT.get(name);
				if(n == null) n = name;
			}
			this.name = n;
		}
		
		@Override
		protected TypeRaw getRaw(){ return this; }
		
		@Override
		public Class<?> generic(IOTypeDB db){
			return (Class<?>)super.generic(db);
		}
		@Override
		public IOType asArrayType(IOTypeDB db){
			return withRaw(getTypeClass(db).arrayType());
		}
		@Override
		protected Class<?> makeGeneric(IOTypeDB db){ return getTypeClass(db); }
		@Override
		public boolean equals(Object o){
			if(o instanceof TypeGeneric that){
				return that.raw.equals(this) &&
				       argsEqual(that.args, List.of());
			}
			return o instanceof TypeRaw raw &&
			       raw.name.equals(name);
		}
		@Override
		public int hashCode(){ return name.hashCode(); }
		@Override
		public String toString(){ return Utils.classNameToHuman(getName()); }
		@Override
		public String toShortString(){ return Utils.classNameToHuman(getName()); }
		@Override
		public IOType withArgs(IOType... args){ return new TypeGeneric(this, List.of(args)); }
		@Override
		public IOType withRaw(Class<?> raw){ return new TypeRaw(raw); }
		
		@Override
		public List<IOType> getArgs(){
			return List.of();
		}
		public boolean isPrimitive(){
			var name = getTypeName();
			return name.length() == 2 && name.charAt(0) == PRIMITIVE_MARKER;
		}
		
		public String getName(){
			if(isPrimitive()){
				return PRIMITIVE_NAMES.get(name).getName();
			}
			return name;
		}
		
		@Override
		public Class<?> getTypeClass(IOTypeDB db){
			var cache = typeClassCache.get();
			cache:
			if(cache != null){
				if(db != null && cache.getClassLoader() instanceof TemplateClassLoader cl){
					var dbcl = db.getTemplateLoader();
					if(cl != dbcl){
						break cache;
					}
				}
				return cache;
			}
			var loaded = loadClass(db);
			typeClassCache = new WeakReference<>(loaded);
			return loaded;
		}
		
		private Class<?> loadClass(IOTypeDB db){
			Objects.requireNonNull(db);
			if(isPrimitive()){
				return PRIMITIVE_NAMES.get(name);
			}
			var name = getTypeName();
			try{
				var builtIn = Class.forName(name);
				try{
					var storedO = db.getDefinitionFromClassName(name);
					if(storedO.map(stored -> !TypeDef.of(builtIn).equals(stored)).orElse(false)){
						Log.trace(
							"""
								{#yellowBuilt in and stored classes are not the same for: #}{}#red
								\t{#redBuilt in:#} {}
								\t{#redStored  :#} {}""",
							() -> {
								return List.of(name, storedO.get(), TypeDef.of(builtIn));
							});
						throw new ClassNotFoundException();
					}
				}catch(IOException e){
					throw new UncheckedIOException("Failed to fetch data from database", e);
				}
				
				return builtIn;
			}catch(ClassNotFoundException e){
				Log.trace("Loading template: {}#yellow", name);
				try{
					return Class.forName(name, true, db.getTemplateLoader());
				}catch(ClassNotFoundException ex){
					throw new RuntimeException(ex);
				}
			}
		}
	}
	
	@IOValue
	public static final class TypeGeneric extends IOType implements RawAndArg{
		
		private final TypeRaw      raw;
		private final List<IOType> args;
		
		public TypeGeneric(TypeRaw raw, List<IOType> args){
			this.raw = raw;
			this.args = List.copyOf(args);
		}
		public TypeGeneric(Class<?> clazz, List<IOType> args){
			this.raw = new TypeRaw(clazz);
			this.args = List.copyOf(args);
		}
		
		@Override
		protected TypeRaw getRaw(){ return raw; }
		@Override
		public List<IOType> getArgs(){ return args; }
		
		public List<Type> genericArgs(IOTypeDB db){
			var res = new ArrayList<Type>(args.size());
			for(IOType arg : args){
				res.add(arg.generic(db));
			}
			return res;
		}
		
		@Override
		protected Type makeGeneric(IOTypeDB db){
			List<Type> args;
			var        ioArgs = getArgs();
			if(ioArgs.size() == 1){
				args = List.of(ioArgs.getFirst().generic(db));
			}else{
				var arr = new Type[ioArgs.size()];
				for(int i = 0; i<ioArgs.size(); i++){
					arr[i] = ioArgs.get(i).generic(db);
				}
				args = List.of(arr);
			}
			return SyntheticParameterizedType.of(raw.getTypeClass(db), args);
		}
		@Override
		public IOType asArrayType(IOTypeDB db){
			return withRaw(getTypeClass(db).arrayType());
		}
		@Override
		public int hashCode(){
			int result = getTypeName().hashCode();
			result = 31*result + args.hashCode();
			return result;
		}
		@Override
		public boolean equals(Object o){
			if(o instanceof TypeRaw that){
				return that.equals(raw) &&
				       argsEqual(List.of(), args);
			}
			return o instanceof TypeGeneric that &&
			       that.raw.equals(raw) &&
			       argsEqual(that.args, args);
		}
		@Override
		public String toString(){
			if(args == null) return getClass().getSimpleName() + IOFieldTools.UNINITIALIZED_FIELD_SIGN;
			return raw + Iters.from(args).joinAsStr(", ", "<", ">");
		}
		@Override
		public String toShortString(){
			return raw.toShortString() + Iters.from(args).joinAsStr(", ", "<", ">", IOType::toShortString);
		}
		@Override
		public IOType withArgs(IOType... args){
			return new TypeGeneric(raw, List.of(args));
		}
		@Override
		public IOType withRaw(Class<?> raw){ return new TypeGeneric(raw, args); }
	}
	
	@IOValue
	public static final class TypeWildcard extends IOType{
		@IONullability(NULLABLE)
		private final IOType  bound;
		private final boolean isLower;
		
		public TypeWildcard(IOType bound, boolean isLower){
			this.bound = bound;
			this.isLower = isLower;
		}
		
		public IOType getBound(){
			return bound;
		}
		
		public boolean isAnyWildcard(){
			return bound == null || (!isLower && bound.getTypeName().equals(Object.class.getName()));
		}
		
		@Override
		protected TypeRaw getRaw(){
			return bound == null? new TypeRaw(Object.class) : bound.getRaw();
		}
		
		@Override
		public Class<?> getTypeClass(IOTypeDB db){
			return Utils.typeToRaw(generic(db));
		}
		
		@Override
		protected Type makeGeneric(IOTypeDB db){
			Type bound = this.bound == null? Object.class : this.bound.generic(db);
			return new SyntheticWildcardType(List.of(bound), isLower);
		}
		@Override
		public IOType asArrayType(IOTypeDB db){
			throw new NotImplementedException("can not wrap " + this + " in array");
		}
		
		@Override
		public int hashCode(){
			int result = Objects.hashCode(bound);
			result = 31*result + (isLower? 0 : 1);
			return result;
		}
		@Override
		public boolean equals(Object o){
			if(!(o instanceof TypeWildcard that)) return false;
			
			if(that.isLower != isLower) return false;
			var b1 = bound;
			var b2 = that.bound;
			
			if(Objects.equals(b1, b2)) return true;
			if(b1 == null || b2 == null){
				var b = b1 == null? b2 : b1;
				return b instanceof TypeRaw raw &&
				       raw.getName().equals(Object.class.getName());
			}
			return false;
		}
		
		@Override
		public String toString(){
			if(isAnyWildcard()){
				return "?";
			}
			return "? " + (isLower? "super" : "extends") + " " + bound;
		}
		@Override
		public String toShortString(){
			if(isAnyWildcard()){
				return "?";
			}
			return "? " + (isLower? "super" : "extends") + " " + bound.toShortString();
		}
		
		public boolean isLower(){
			return isLower;
		}
	}
	
	@IOValue
	public static final class TypeNameArg extends IOType{
		
		private final TypeRaw parent;
		private final String  name;
		
		public TypeNameArg(Class<?> parent, String name){
			this(new TypeRaw(parent), name);
		}
		public TypeNameArg(TypeRaw parent, String name){
			this.parent = parent;
			this.name = name;
		}
		
		public TypeRaw getParent(){ return parent; }
		public String getName()   { return name; }
		
		@Override
		protected TypeRaw getRaw(){
			throw new UnsupportedOperationException();
		}
		
		@Override
		public Class<?> getTypeClass(IOTypeDB db){
			return Utils.typeToRaw(generic(db));
		}
		
		private record GenTypeVariable(String name, Type[] bounds, Class<?> declaration) implements TypeVariable<Class<?>>{
			@Override
			public Type[] getBounds(){ return bounds.clone(); }
			@Override
			public Class<?> getGenericDeclaration(){ return declaration; }
			@Override
			public String getName(){ return name; }
			
			@Override
			public <T extends Annotation> T getAnnotation(Class<T> annotationClass){ return null; }
			@Override
			public Annotation[] getAnnotations(){ return new Annotation[0]; }
			@Override
			public Annotation[] getDeclaredAnnotations(){ return new Annotation[0]; }
			@Override
			public AnnotatedType[] getAnnotatedBounds(){ return new AnnotatedType[0]; }
			@Override
			public boolean equals(Object obj){
				return obj instanceof TypeVariable<?> that &&
				       that.getGenericDeclaration().equals(declaration) &&
				       that.getName().equals(name);
			}
			@Override
			public String toString(){
				return name + ": " + Iters.from(bounds).joinAsStr(" & ", Utils::typeToHuman);
			}
		}
		
		
		@Override
		protected Type makeGeneric(IOTypeDB db){
			var    name        = this.name;
			var    declaration = parent.getTypeClass(db);
			Type[] bounds      = findBounds(declaration, name);
			return new GenTypeVariable(name, bounds, declaration);
		}
		@Override
		public IOType asArrayType(IOTypeDB db){
			throw new NotImplementedException("can not wrap " + this + " in array");
		}
		
		public Type[] findBounds(IOTypeDB db){
			var name        = this.name;
			var declaration = parent.getTypeClass(db);
			return findBounds(declaration, name);
		}
		
		private static Type[] findBounds(Class<?> declaration, String name){
			for(var parm : declaration.getTypeParameters()){
				if(parm.getName().equals(name)){
					return parm.getBounds();
				}
			}
			throw new IllegalStateException(
				"Type variable has name of \"" + name + "\" but " +
				declaration.getName() + " has no such type parameter"
			);
		}
		
		@Override
		public int hashCode(){
			int result = Objects.hashCode(parent);
			result = 31*result + name.hashCode();
			return result;
		}
		@Override
		public boolean equals(Object o){
			return o instanceof TypeNameArg that &&
			       that.name.equals(name) &&
			       Objects.equals(that.parent, parent);
		}
		@Override
		public String toString(){
			var gen = getCachedGeneric();
			return gen != null? ": " + gen : name;
		}
		@Override
		public String toShortString(){
			return toString();
		}
	}
	
	public static IOType getArg(IOType type, int idx){
		if(type instanceof TypeGeneric gen){
			return gen.getArgs().get(idx);
		}
		throw new IllegalArgumentException(type + " does not have arguments");
	}
	public static List<IOType> getArgs(IOType type){
		if(type instanceof TypeGeneric gen){
			return gen.getArgs();
		}
		return List.of();
	}
	
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
	
	public static IOType ofFlat(Class<?>... args)         { return of(readType(Arrays.asList(args).iterator())); }
	public static IOType of(Class<?> raw, Type... args)   { return of(raw, List.of(args)); }
	public static IOType of(Class<?> raw, List<Type> args){ return of(SyntheticParameterizedType.of(raw, args)); }
	public static IOType of(Class<?> raw, IOType... args) { return new TypeGeneric(raw, List.of(args)); }
	
	public static IOType of(Type genericType){
		return switch(genericType){
			case Class<?> cl -> of(cl);
			case ParameterizedType parm -> of(parm);
			case TypeVariable<?> var -> of(var);
			case WildcardType wild -> of(wild);
			case GenericArrayType a -> {
				var comp = of(a.getGenericComponentType());
				yield comp.asArrayType(null);
			}
			default -> throw new NotImplementedException(genericType.getClass().getName());
		};
	}
	
	public static IOType of(Class<?> raw){
		return new IOType.TypeRaw(raw);
	}
	
	public static IOType of(ParameterizedType parm){
		var args = parm.getActualTypeArguments();
		var res  = new ArrayList<IOType>(args.length);
		for(Type arg : args){
			res.add(of(arg));
		}
		return new IOType.TypeGeneric(
			(Class<?>)parm.getRawType(),
			res
		);
	}
	public static IOType of(TypeVariable<?> var){
		if(!(var.getGenericDeclaration() instanceof Class<?> parent)){
			throw new NotImplementedException("TypeVariable supported only on classes for now");
		}
		return new TypeNameArg(parent, var.getName());
	}
	public static IOType of(WildcardType wild){
		Type    tBound;
		boolean isLower;
		{
			var lowerBounds = wild.getLowerBounds();
			isLower = lowerBounds.length>0;
			if(lowerBounds.length == 0){
				var upperBounds = wild.getUpperBounds();
				if(upperBounds.length == 0 || Object.class == upperBounds[0]){
					tBound = null;
				}else{
					if(upperBounds.length == 1) throw new NotImplementedException("Multiple bounds not implemented");//TODO
					tBound = upperBounds[0];
				}
			}else{
				if(lowerBounds.length != 1) throw new NotImplementedException("Multiple bounds not implemented");
				tBound = lowerBounds[0];
			}
		}
		
		IOType bound = null;
		if(tBound != null){
			bound = of(tBound);
		}
		
		return new IOType.TypeWildcard(bound, isLower);
	}
	
	private Type genericCache;
	
	public IOType(){ }
	
	public String getTypeName(){
		return getRaw().name;
	}
	protected abstract TypeRaw getRaw();
	protected Type getCachedGeneric(){
		return genericCache;
	}
	
	public Class<?> getTypeClass(IOTypeDB db){
		return getRaw().getTypeClass(db);
	}
	
	protected abstract Type makeGeneric(IOTypeDB db);
	public Type generic(IOTypeDB db){
		var gen = genericCache;
		if(genericCache == null){
			genericCache = gen = makeGeneric(db);
		}
		return gen;
	}
	
	public Set<TypeRaw> collectRaws(IOTypeDB db){
		return switch(this){
			case IOType.TypeRaw typeRaw -> {
				yield Set.of(typeRaw);
			}
			case IOType.TypeGeneric typeGeneric -> {
				var args = typeGeneric.getArgs();
				var res  = HashSet.<TypeRaw>newHashSet(args.size() + 1);
				res.add(typeGeneric.getRaw());
				for(var arg : typeGeneric.getArgs()){
					res.addAll(arg.collectRaws(db));
				}
				yield res;
			}
			case IOType.TypeWildcard typeWildcard -> {
				yield typeWildcard.getBound().collectRaws(db);
			}
			case IOType.TypeNameArg typeNameArg -> {
				var bounds = typeNameArg.findBounds(db);
				var res    = HashSet.<TypeRaw>newHashSet(bounds.length + 1);
				for(Type bound : bounds){
					var b = IOType.of(bound);
					res.addAll(b.collectRaws(db));
				}
				yield res;
			}
		};
	}
	
	public abstract IOType asArrayType(IOTypeDB db);
	
	private static boolean argsEqual(List<IOType> a, List<IOType> b){
		if(a.size() != b.size()){
			if(a.size() != 0 && b.size() != 0) return false;
			var obj = TypeRaw.OBJ;
			for(var arg : a.size() != 0? a : b){
				if(!arg.equals(obj)){
					return false;
				}
			}
			return true;
		}
		return a.equals(b);
	}
	
	@Override
	public abstract int hashCode();
	@Override
	public abstract boolean equals(Object o);
	@Override
	public abstract String toString();
	@Override
	public abstract String toShortString();
}
