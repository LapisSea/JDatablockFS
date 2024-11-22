package com.lapissea.dfs.internal;

import com.lapissea.dfs.Utils;
import com.lapissea.dfs.exceptions.MissingConstructor;
import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.utils.WeakKeyValueMap;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.NotNull;

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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

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
	
	private static final List<AccessProvider> ACCESS_PROVIDERS   = new CopyOnWriteArrayList<>();
	private static final AccessProvider       UNOPTIMIZED_ACCESS = new AccessProvider.UnoptimizedAccessProvider();
	
	private static final ThreadPoolExecutor GC_EXEC = new ThreadPoolExecutor(
		0, 1, 200, TimeUnit.MILLISECONDS,
		new ArrayBlockingQueue<>(2),
		new ThreadPoolExecutor.DiscardPolicy()
	);
	
	static{
		registerProvider(new AccessProvider.DirectLookup(MethodHandles.publicLookup()));
	}
	
	public static void registerProvider(AccessProvider provider){
		ACCESS_PROVIDERS.add(provider);
		
		if(GC_EXEC.getQueue().size() == 0){
			clean();
		}
	}
	private static void clean(){
		GC_EXEC.execute(() -> {
			System.gc();
			try{
				Thread.sleep(200);
			}catch(InterruptedException e){ throw new RuntimeException(e); }
			removeDefunct();
		});
	}
	
	private static void removeDefunct(){
		while(AccessProvider.DEFUNCT_REF_QUEUE.poll() != null) ;
		ACCESS_PROVIDERS.removeIf(AccessProvider::isDefunct);
	}
	
	public static void addLookup(MethodHandles.Lookup lookup){
		try{
			AccessUtils.requireModes(lookup, Mode.PRIVATE, Mode.MODULE);
		}catch(IllegalAccessException e){
			throw new IllegalArgumentException(e);
		}
		registerProvider(new AccessProvider.DirectLookup(lookup));
	}
	
	public static <FInter, T extends FInter> T makeLambda(Class<?> type, String name, Class<FInter> functionalInterface) throws IllegalAccessException{
		var match = Iters.from(type.getMethods()).filter(m -> m.getName().equals(name)).limit(2).toModList();
		if(match.isEmpty()){
			match = Iters.from(type.getDeclaredMethods()).filter(m -> m.getName().equals(name)).limit(2).toModList();
		}
		if(match.size()>1) throw new IllegalArgumentException("Ambiguous method name");
		return makeLambda(match.getFirst(), functionalInterface);
	}
	
	public static <FInter, T extends FInter> T makeLambda(Method method, Class<FInter> functionalInterface) throws IllegalAccessException{
		return access(AccessProvider::makeLambda, method, functionalInterface, "failed to create lambda\n  Method: {}#red", true);
	}
	public static <FInter, T extends FInter> T makeLambda(Constructor<?> constructor, Class<FInter> functionalInterface) throws IllegalAccessException{
		return makeLambda(constructor, functionalInterface, true);
	}
	public static <FInter, T extends FInter> T makeLambda(Constructor<?> constructor, Class<FInter> functionalInterface, boolean optimized) throws IllegalAccessException{
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
	
	public static VarHandle makeVarHandle(Field field) throws IllegalAccessException{
		return access(AccessProvider::unreflect, field, "failed to create VarHandle\n  Field: {}#red", true);
	}
	public static MethodHandle makeMethodHandle(@NotNull Constructor<?> constructor) throws IllegalAccessException{
		return access(AccessProvider::unreflect, constructor, "failed to create MethodHandle\n  Constructor: {}#red", true);
	}
	public static MethodHandle makeMethodHandle(@NotNull Method method) throws IllegalAccessException{
		return access(AccessProvider::unreflect, method, "failed to create MethodHandle\n  Method: {}#red", true);
	}
	public static Class<?> defineClass(Class<?> target, byte[] bytecode) throws IllegalAccessException{
		return access(AccessProvider::defineClass, target, bytecode, "failed to define class\n  Target: {}#red", true);
	}
	public static AccessProvider findAccess(Class<?> target, Mode... modes) throws IllegalAccessException{
		return access(AccessProvider::adapt, target, modes, "failed to find AccessProvider\n  Target: {}#red", true);
	}
	
	
	public static <FInter, T extends FInter> T findConstructor(@NotNull Class<?> clazz, Class<FInter> functionalInterface, boolean optimized) throws IllegalAccessException{
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
	T findConstructorArgs(@NotNull Class<?> clazz, Class<FInter> functionalInterface, boolean optimized, Class<?>... parameterTypes) throws IllegalAccessException{
		try{
			Constructor<?> lconst;
			if(Modifier.isPrivate(clazz.getModifiers())){
				lconst = clazz.getDeclaredConstructor(parameterTypes);
			}else{
				lconst = clazz.getConstructor(parameterTypes);
			}
			
			return makeLambda(lconst, functionalInterface, optimized);
		}catch(IllegalAccessException e){
			throw e;
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
		T call(AccessProvider acc, V value) throws IllegalAccessException, AccessProvider.Defunct;
	}
	
	private interface AccessLambda2<T, V1, V2>{
		T call(AccessProvider acc, V1 value1, V2 value2) throws IllegalAccessException, AccessProvider.Defunct;
	}
	
	private static <T, V1, V2> T access(AccessLambda2<T, V1, V2> fn, V1 value1, V2 value2, String errorMessage, boolean optimized) throws IllegalAccessException{
		return access((acc, v1) -> fn.call(acc, v1, value2), value1, errorMessage, optimized);
	}
	private static <T, V> T access(AccessLambda<T, V> fn, V value, String errorMessage, boolean optimized) throws IllegalAccessException{
		if(!optimized){
			try{
				return fn.call(UNOPTIMIZED_ACCESS, value);
			}catch(Throwable ignore){ }
		}
		
		checkClean();
		
		for(var provider : ACCESS_PROVIDERS){
			try{
				return fn.call(provider, value);
			}catch(AccessProvider.Defunct e){
				ACCESS_PROVIDERS.remove(provider);
			}catch(IllegalAccessException ignored){ }
		}
		
		// load and try again
		switch(value){
			case Class<?> cl -> Utils.ensureClassLoaded(cl);
			case Method v -> Utils.ensureClassLoaded(v.getDeclaringClass());
			case Field v -> Utils.ensureClassLoaded(v.getDeclaringClass());
			case Constructor<?> v -> Utils.ensureClassLoaded(v.getDeclaringClass());
			default -> { }
		}
		
		List<Throwable> err = null;
		for(var provider : ACCESS_PROVIDERS){
			try{
				return fn.call(provider, value);
			}catch(AccessProvider.Defunct e){
				ACCESS_PROVIDERS.remove(provider);
			}catch(IllegalAccessException e){
				if(err == null) err = new ArrayList<>();
				err.add(e);
			}
		}
		
		var msg = Iters.concat1N(
			Log.fmt(errorMessage, value),
			err == null? List.of() : err
		).joinAsStr("\n");
		
		throw new IllegalAccessException(msg);
	}
	
	private static void checkClean(){
		boolean cleanup = false;
		while(AccessProvider.DEFUNCT_REF_QUEUE.poll() != null){
			cleanup = true;
		}
		
		if(!cleanup){
			var index = (int)(Math.random()*(ACCESS_PROVIDERS.size() - 1));
			try{
				var acc = ACCESS_PROVIDERS.get(index);
				if(acc.isDefunct()){
					cleanup = true;
				}
			}catch(IndexOutOfBoundsException ignore){ }
		}
		
		if(cleanup){
			removeDefunct();
		}
	}
}
