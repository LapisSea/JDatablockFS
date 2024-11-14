package com.lapissea.dfs.internal;

import com.lapissea.dfs.Utils;
import com.lapissea.dfs.exceptions.MissingAccessConsent;
import com.lapissea.dfs.exceptions.MissingConstructor;
import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.NewObj;
import com.lapissea.dfs.utils.WeakKeyValueMap;
import com.lapissea.dfs.utils.iterableplus.IterablePP;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.dfs.utils.iterableplus.Match;
import com.lapissea.dfs.utils.iterableplus.Match.Some;
import com.lapissea.util.NotNull;
import com.lapissea.util.UtilL;
import com.lapissea.util.function.TriFunction;
import com.lapissea.util.function.UnsafeSupplier;

import java.lang.invoke.CallSite;
import java.lang.invoke.LambdaConversionException;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.LongFunction;

import static java.lang.invoke.MethodHandles.Lookup.MODULE;
import static java.lang.invoke.MethodHandles.Lookup.PRIVATE;
import static java.lang.invoke.MethodHandles.Lookup.PUBLIC;

@SuppressWarnings("unchecked")
public final class Access{
	public enum Mode{
		PUBLIC(MethodHandles.Lookup.PUBLIC),
		MODULE(MethodHandles.Lookup.MODULE),
		PACKAGE(MethodHandles.Lookup.PACKAGE),
		PROTECTED(MethodHandles.Lookup.PROTECTED),
		PRIVATE(MethodHandles.Lookup.PRIVATE),
		ORIGINAL(MethodHandles.Lookup.ORIGINAL),
		UNCONDITIONAL(MethodHandles.Lookup.UNCONDITIONAL),
		;
		
		final int flag;
		Mode(int flag){ this.flag = flag; }
	}
	
	private static final Map<Class<?>, MethodHandles.Lookup> PRIVATE_LOOKUPS = new ConcurrentHashMap<>();
	
	public static void addLookup(MethodHandles.Lookup lookup){
		if((lookup.lookupModes()&(PRIVATE|MODULE)) != (PRIVATE|MODULE)) badLookup(lookup);
		PRIVATE_LOOKUPS.put(lookup.lookupClass(), lookup);
	}
	private static IterablePP<Mode> getModes(MethodHandles.Lookup lookup){
		return Iters.from(Mode.values())
		            .filter(e -> UtilL.checkFlag(lookup.lookupModes(), e.flag));
	}
	private static void badLookup(MethodHandles.Lookup lookup){
		var modes = getModes(lookup).joinAsStr(", ", "[", "]");
		
		throw new IllegalArgumentException(
			Log.fmt("Lookup of {}#red must have {#yellow[PRIVATE, MODULE]#} access but has {}#yellow!", lookup, modes)
		);
	}
	
	public static <FInter, T extends FInter> T makeLambda(Class<?> type, String name, Class<FInter> functionalInterface){
		var match = Iters.from(type.getMethods()).filter(m -> m.getName().equals(name)).limit(2).toModList();
		if(match.isEmpty()){
			match = Iters.from(type.getDeclaredMethods()).filter(m -> m.getName().equals(name)).limit(2).toModList();
		}
		if(match.size()>1) throw new IllegalArgumentException("Ambiguous method name");
		return makeLambda(match.getFirst(), functionalInterface);
	}
	
	public static <FInter, T extends FInter> T makeLambda(Method method, Class<FInter> functionalInterface){
		try{
			method.setAccessible(true);
			var lookup = getLookup(method.getDeclaringClass(), Mode.PRIVATE, Mode.MODULE);
			return createFromCallSite(functionalInterface, lookup, lookup.unreflect(method));
		}catch(Throwable e){
			throw new RuntimeException("failed to create lambda for method " + method + " with " + functionalInterface, e);
		}
	}
	
	public static <FInter, T extends FInter> T makeLambda(Constructor<?> constructor, Class<FInter> functionalInterface){
		return makeLambda(constructor, functionalInterface, true);
	}
	public static <FInter, T extends FInter> T makeLambda(Constructor<?> constructor, Class<FInter> functionalInterface, boolean optimized){
		try{
			constructor.setAccessible(true);
			
			if(!optimized){
				var unop = Access.<FInter, T>makeLambdaUnop(constructor, functionalInterface);
				if(unop != null) return unop;
			}
			
			var lookup = getLookup(constructor.getDeclaringClass(), Mode.PRIVATE, Mode.MODULE);
			return createFromCallSite(functionalInterface, lookup, lookup.unreflectConstructor(constructor));
		}catch(Throwable e){
			throw new RuntimeException("failed to create lambda for constructor " + constructor + " with " + functionalInterface, e);
		}
	}
	
	public record DumbConstructorImpl<Interf>(
		Class<Interf> functionalInterfaceType,
		Function<Constructor<?>, Interf> make
	){ }
	
	private static <T> T refl(UnsafeSupplier<T, ReflectiveOperationException> get){
		try{
			return get.get();
		}catch(ReflectiveOperationException e){
			throw new RuntimeException(e);
		}
	}
	
	private static final List<DumbConstructorImpl<?>> DUMB_CONSTRUCTORS = List.of(
		new DumbConstructorImpl<>(Function.class, ctor -> o -> refl(() -> ctor.newInstance(o))),
		new DumbConstructorImpl<>(IntFunction.class, ctor -> o -> refl(() -> ctor.newInstance(o))),
		new DumbConstructorImpl<>(LongFunction.class, ctor -> o -> refl(() -> ctor.newInstance(o))),
		new DumbConstructorImpl<>(BiFunction.class, ctor -> (a, b) -> refl(() -> ctor.newInstance(a, b))),
		new DumbConstructorImpl<>(TriFunction.class, ctor -> (a, b, c) -> refl(() -> ctor.newInstance(a, b, c))),
		new DumbConstructorImpl<>(NewObj.class, ctor -> () -> refl(ctor::newInstance)),
		new DumbConstructorImpl<>(NewObj.Instance.class, ctor -> () -> refl(() -> (IOInstance<?>)ctor.newInstance()))
	);
	
	public static <FInter, T extends FInter> T makeLambdaUnop(Constructor<?> constructor, Class<FInter> functionalInterface){
		for(var ctor : DUMB_CONSTRUCTORS){
			if(ctor.functionalInterfaceType == functionalInterface){
				return (T)ctor.make.apply(constructor);
			}
		}
		return null;
	}
	
	public static <FInter, T extends FInter> T makeLambda(Constructor<?> constructor, MethodHandles.Lookup lookup, Class<FInter> functionalInterface){
		if(lookup.lookupClass() != constructor.getDeclaringClass()){
			throw new IllegalArgumentException(lookup.lookupClass().getName() + " != " + constructor.getDeclaringClass().getName());
		}
		try{
			constructor.setAccessible(true);
			return createFromCallSite(functionalInterface, lookup, lookup.unreflectConstructor(constructor));
		}catch(Throwable e){
			throw new RuntimeException("failed to create lambda for constructor " + constructor + " with " + functionalInterface, e);
		}
	}
	
	private static <FInter, T extends FInter> T createFromCallSite(Class<FInter> functionalInterface, MethodHandles.Lookup lookup, MethodHandle handle) throws Throwable{
		var site = createCallSite(functionalInterface, lookup, handle);
		return Objects.requireNonNull((T)site.getTarget().invoke());
	}
	
	private static <FInter> CallSite createCallSite(Class<FInter> functionalInterface, MethodHandles.Lookup lookup, MethodHandle handle) throws LambdaConversionException{
		Method     functionalInterfaceFunction = getFunctionalMethod(functionalInterface);
		MethodType signature                   = MethodType.methodType(functionalInterfaceFunction.getReturnType(), functionalInterfaceFunction.getParameterTypes());
		
		var funName    = functionalInterfaceFunction.getName();
		var funType    = MethodType.methodType(functionalInterface);
		var handleType = handle.type();
		
		return LambdaMetafactory.metafactory(lookup, funName, funType, signature, handle, handleType);
	}
	
	
	public static <FInter> Method getFunctionalMethod(Class<FInter> functionalInterface){
		var methods = Iters.from(functionalInterface.getMethods())
		                   .filter(m -> !Modifier.isStatic(m.getModifiers()) && Modifier.isAbstract(m.getModifiers()))
		                   .toModList();
		if(methods.size() != 1){
			throw new IllegalArgumentException(functionalInterface + " is not a functional interface!");
		}
		return methods.getFirst();
	}
	
	private static MethodHandles.Lookup getBestLookup(Class<?> clazz, boolean clinitCall) throws IllegalAccessException{
		if(getPrivateLookup(clazz, clinitCall) instanceof Some(var lookup)){
			return lookup;
		}
		
		var parents = Iters.values(PRIVATE_LOOKUPS).filter(e -> UtilL.instanceOf(e.lookupClass(), clazz));
		if(parents.matchFirst() instanceof Some(var parentLookup)){
			try{
				return MethodHandles.privateLookupIn(clazz, parentLookup);
			}catch(IllegalAccessException ignore){ }
		}
		
		var moduleValues  = Iters.values(PRIVATE_LOOKUPS).filter(e -> e.lookupClass().getModule().equals(clazz.getModule())).bake();
		var packageValues = moduleValues.filter(e -> e.lookupClass().getPackageName().equals(clazz.getPackageName()));
		
		if(packageValues.matchFirst() instanceof Some(var packageSibling)){
			try{
				return MethodHandles.privateLookupIn(clazz, packageSibling);
			}catch(IllegalAccessException ignore){ }
		}
		
		if(moduleValues.matchFirst() instanceof Some(var moduleAccess)){
			try{
				return MethodHandles.privateLookupIn(clazz, moduleAccess);
			}catch(IllegalAccessException ignore){ }
		}
		
		return MethodHandles.publicLookup();
	}
	
	private static Match<MethodHandles.Lookup> getPrivateLookup(Class<?> clazz, boolean clinitCall) throws IllegalAccessException{
		var givenLookup = PRIVATE_LOOKUPS.get(clazz);
		if(givenLookup != null){
			assert givenLookup.lookupClass() == clazz;
			return Match.of(givenLookup);
		}
		var outer = clazz;
		while(true){
			outer = outer.getEnclosingClass();
			if(outer == null) break;
			
			var outerLookup = PRIVATE_LOOKUPS.get(outer);
			if(clinitCall && outerLookup == null){
				Utils.ensureClassLoaded(outer);
				outerLookup = PRIVATE_LOOKUPS.get(outer);
			}
			if(outerLookup != null){
				assert outerLookup.lookupClass() == outer;
				var lookup = MethodHandles.privateLookupIn(clazz, outerLookup);
				return Match.of(lookup);
			}
		}
		return Match.empty();
	}
	
	public static MethodHandles.Lookup getLookup(Class<?> clazz, Mode... requiredModes) throws IllegalAccessException{
		if(requiredModes.length == 1 && requiredModes[0] == Mode.PUBLIC){
			return MethodHandles.publicLookup();
		}
		var lookup = getBestLookup(clazz, false);
		
		if(lookup == MethodHandles.publicLookup()){
			Utils.ensureClassLoaded(clazz);
			lookup = getBestLookup(clazz, true);
			if(lookup != MethodHandles.publicLookup()){
				return lookup;
			}
		}
		
		var modes        = (lookup == MethodHandles.publicLookup()? Iters.of(Mode.PUBLIC) : getModes(lookup)).asCollection();
		var missingModes = Iters.from(requiredModes).filter(m -> !modes.contains(m));
		if(missingModes.hasAny()){
			String snippet = clazz.isInterface()?
			                 "{#greenVoid _acc = IOInstance.allowFullAccessI(MethodHandles.lookup());#}" :
			                 "{#greenstatic{ allowFullAccess(MethodHandles.lookup()); }#}";
			
			throw new MissingAccessConsent(
				"fmt",
				"""
					No consent was provided for {}#red and required attributes can not be accessed!
					  Requested modes: {}#yellow
					  Available modes: {}#red
					  {#yellowNote:#} Have you added {}#green to your {}?""",
				clazz,
				missingModes.joinAsStr(", "),
				modes.joinAsStr(", "),
				snippet, clazz.isInterface()? "interface" : "class"
			);
		}
		
		if(requiredModes.length == 0){
			return lookup.dropLookupMode(PUBLIC);
		}else{
			var toStrip = EnumSet.allOf(Mode.class);
			toStrip.removeAll(List.of(Mode.ORIGINAL, Mode.UNCONDITIONAL));
			
			while(!toStrip.isEmpty()){
				var current = lookup;
				var okStrips = Iters.from(toStrip).filter(mode -> {
					var nm     = current.dropLookupMode(mode.flag);
					var sModes = getModes(nm).toSet();
					return Iters.from(requiredModes).allMatch(sModes::contains);
				}).matchFirst();
				if(okStrips instanceof Some(var okMode)){
					lookup = current.dropLookupMode(okMode.flag);
					toStrip.remove(okMode);
				}else{
					break;
				}
			}
			return lookup;
		}
	}
	
	public static VarHandle makeVarHandle(Field field){
		try{
			var requiredMode = Iters.of(Mode.PRIVATE, Mode.PROTECTED, Mode.PUBLIC)
			                        .firstMatching(m -> (field.getModifiers()&m.flag) != 0).orElseThrow();
			
			var lookup = getLookup(field.getDeclaringClass(), requiredMode);
			return lookup.unreflectVarHandle(field);
		}catch(Throwable e){
			throw failMakeHandle("failed to create VarHandle\nField: " + field, e);
		}
	}
	
	public static MethodHandle makeMethodHandle(@NotNull Constructor<?> constructor){
		try{
			var lookup = getLookup(constructor.getDeclaringClass());
			constructor.setAccessible(true);
			return lookup.unreflectConstructor(constructor);
		}catch(Throwable e){
			throw failMakeHandle("failed to create MethodHandle\nConstructor: " + constructor, e);
		}
	}
	public static MethodHandle makeMethodHandle(@NotNull Method method){
		try{
			var lookup = getLookup(method.getDeclaringClass());
			method.setAccessible(true);
			return lookup.unreflect(method);
		}catch(Throwable e){
			throw failMakeHandle("failed to create MethodHandle\nMethod: " + method, e);
		}
	}
	private static RuntimeException failMakeHandle(String message, Throwable e){
		if(e instanceof MissingAccessConsent mac){
			var er = new RuntimeException(message);
			mac.addSuppressed(er);
			throw mac;
		}
		throw new RuntimeException(message, e);
	}
	
	
	public static <FInter, T extends FInter> T findConstructor(@NotNull Class<?> clazz, Class<FInter> functionalInterface, boolean optimized){
		Class<?>[] args = getArgsU(functionalInterface);
		return findConstructorArgs(clazz, functionalInterface, optimized, args);
	}
	
	private static final WeakKeyValueMap<Class<?>, Class<?>[]> ARG_CACHE = new WeakKeyValueMap.Sync<>();
	public static <FInter> Class<?>[] getArgs(Class<FInter> functionalInterface){
		return getArgsU(functionalInterface).clone();
	}
	private static <FInter> Class<?>[] getArgsU(Class<FInter> functionalInterface){
		var ref = ARG_CACHE.get(functionalInterface);
		if(ref != null) return ref;
		
		var args = getFunctionalMethod(functionalInterface).getParameterTypes();
		ARG_CACHE.put(functionalInterface, args);
		return args;
	}
	
	@NotNull
	public static <FInter, T extends FInter> T findConstructorArgs(@NotNull Class<?> clazz, Class<FInter> functionalInterface, boolean optimized, Class<?>... parameterTypes){
		try{
			Constructor<?> lconst;
			if(Modifier.isPrivate(clazz.getModifiers())){
				lconst = clazz.getDeclaredConstructor(parameterTypes);
			}else{
				lconst = clazz.getConstructor(parameterTypes);
			}
			
			return makeLambda(lconst, functionalInterface, optimized);
		}catch(ReflectiveOperationException ce){
			
			try{
				Method of = clazz.getMethod("of", parameterTypes);
				if(!Modifier.isStatic(of.getModifiers())) throw new ReflectiveOperationException(of + " not static");
				if(!Modifier.isPublic(of.getModifiers())) throw new ReflectiveOperationException(of + " not public");
				if(!of.getReturnType().equals(clazz)) throw new ReflectiveOperationException(of + " does not return " + clazz);
				
				return makeLambda(of, functionalInterface);
			}catch(ReflectiveOperationException ofe){
				var argStr = switch(parameterTypes.length){
					case 0 -> "empty arguments";
					case 1 -> "argument of " + parameterTypes[0];
					default -> "arguments of " + Arrays.toString(parameterTypes);
				};
				var e = new MissingConstructor("fmt", "{}#red does not have a valid constructor or \"of\" static method with {}#yellow", clazz.getName(), argStr);
				e.addSuppressed(ce);
				e.addSuppressed(ofe);
				throw e;
			}
		}
	}
}
