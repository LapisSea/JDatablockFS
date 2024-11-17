package com.lapissea.dfs.internal;

import com.lapissea.dfs.exceptions.MissingConstructor;
import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.utils.WeakKeyValueMap;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.NotNull;

import java.lang.invoke.LambdaConversionException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

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
	
	private static final CopyOnWriteArrayList<AccessProvider> ACCESS_PROVIDERS   = new CopyOnWriteArrayList<>();
	private static final AccessProvider                       UNOPTIMIZED_ACCESS = new AccessProvider.UnoptimizedAccessProvider();
	
	public static void addLookup(MethodHandles.Lookup lookup){
		AccessUtils.requireModes(lookup, Mode.PRIVATE, Mode.MODULE);
		ACCESS_PROVIDERS.add(new AccessProvider.DirectLookup(lookup));
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
		return access(AccessProvider::makeLambda, method, functionalInterface, "failed to create lambda\n  Method: {}#red", true);
	}
	public static <FInter, T extends FInter> T makeLambda(Constructor<?> constructor, Class<FInter> functionalInterface){
		return makeLambda(constructor, functionalInterface, true);
	}
	public static <FInter, T extends FInter> T makeLambda(Constructor<?> constructor, Class<FInter> functionalInterface, boolean optimized){
		return access(AccessProvider::makeLambda, constructor, functionalInterface, "failed to create lambda\n  Constructor: {}#red", optimized);
	}
	
	public static <FInter, T extends FInter> T makeLambda(Constructor<?> constructor, MethodHandles.Lookup lookup, Class<FInter> functionalInterface){
		if(lookup.lookupClass() != constructor.getDeclaringClass()){
			throw new IllegalArgumentException(lookup.lookupClass().getName() + " != " + constructor.getDeclaringClass().getName());
		}
		try{
			var access = new AccessProvider.DirectLookup(lookup);
			return access.makeLambda(constructor, functionalInterface);
		}catch(Throwable e){
			throw new RuntimeException("failed to create lambda for constructor " + constructor + " with " + functionalInterface, e);
		}
	}
	
	public static VarHandle makeVarHandle(Field field){
		return access(AccessProvider::unreflect, field, "failed to create VarHandle\n  Field: {}#red", true);
	}
	public static MethodHandle makeMethodHandle(@NotNull Constructor<?> constructor){
		return access(AccessProvider::unreflect, constructor, "failed to create MethodHandle\n  Constructor: {}#red", true);
	}
	public static MethodHandle makeMethodHandle(@NotNull Method method){
		return access(AccessProvider::unreflect, method, "failed to create MethodHandle\n  Method: {}#red", true);
	}
	public static Class<?> defineClass(Class<?> target, byte[] bytecode){
		return access(AccessProvider::defineClass, target, bytecode, "failed to define class\n  Target: {}#red", true);
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
		
		var args = AccessUtils.getFunctionalMethod(functionalInterface).getParameterTypes();
		ARG_CACHE.put(functionalInterface, args);
		return args;
	}
	
	@NotNull
	public static <FInter, T extends FInter>
	T findConstructorArgs(@NotNull Class<?> clazz, Class<FInter> functionalInterface, boolean optimized, Class<?>... parameterTypes){
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
	
	
	private interface AccessLambda<T, V>{
		T call(AccessProvider acc, V value) throws IllegalAccessException, LambdaConversionException, AccessProvider.Defunct;
	}
	
	private interface AccessLambda2<T, V1, V2>{
		T call(AccessProvider acc, V1 value1, V2 value2) throws IllegalAccessException, LambdaConversionException, AccessProvider.Defunct;
	}
	
	private static <T, V1, V2> T access(AccessLambda2<T, V1, V2> fn, V1 value1, V2 value2, String errorMessage, boolean optimized){
		return access((acc, v1) -> fn.call(acc, v1, value2), value1, errorMessage, optimized);
	}
	private static <T, V> T access(AccessLambda<T, V> fn, V value, String errorMessage, boolean optimized){
		if(!optimized){
			try{
				return fn.call(UNOPTIMIZED_ACCESS, value);
			}catch(Throwable ignore){ }
		}
		
		List<Throwable> err = null;
		for(var provider : ACCESS_PROVIDERS){
			try{
				return fn.call(provider, value);
			}catch(AccessProvider.Defunct e){
				ACCESS_PROVIDERS.remove(provider);
			}catch(IllegalAccessException|LambdaConversionException e){
				if(err == null) err = new ArrayList<>();
				err.add(e);
			}
		}
		if(err == null) err = new ArrayList<>();
		throw new RuntimeException(Iters.concat1N(
			Log.fmt(errorMessage, value),
			err
		).joinAsStr("\n"));
	}
}
