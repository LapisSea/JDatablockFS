package com.lapissea.dfs.internal;

import com.lapissea.dfs.internal.Access.Mode;
import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.NewObj;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.ShouldNeverHappenError;
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
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.LongFunction;
import java.util.function.Supplier;

public interface AccessProvider{
	
	class Defunct extends Exception{ }
	
	class UnoptimizedAccessProvider implements AccessProvider{
		
		@Override
		public VarHandle unreflect(Field field){ throw new UnsupportedOperationException(); }
		@Override
		public MethodHandle unreflect(Method method){ throw new UnsupportedOperationException(); }
		@Override
		public MethodHandle unreflect(Constructor<?> constructor){ throw new UnsupportedOperationException(); }
		
		@Override
		public <InterfType, T extends InterfType> T makeLambda(Method method, Class<InterfType> functionalInterface){
			throw NotImplementedException.infer();//TODO: implement UnoptimizedAccessProvider.makeLambda()
		}
		@Override
		public <InterfType, T extends InterfType> T makeLambda(Constructor<?> constructor, Class<InterfType> functionalInterface){
			return makeLambdaUnop(constructor, functionalInterface);
		}
		
		@Override
		public Class<?> defineClass(Class<?> target, byte[] bytecode){ throw new UnsupportedOperationException(); }
		
		private static <T> T refl(UnsafeSupplier<T, ReflectiveOperationException> get){
			try{
				return get.get();
			}catch(ReflectiveOperationException e){
				throw new RuntimeException(e);
			}
		}
		
		@SuppressWarnings("unchecked")
		private static <InterfType, T extends InterfType> T makeLambdaUnop(Constructor<?> ctor, Class<InterfType> functionalInterface){
			ctor.setAccessible(true);
			
			if(functionalInterface == NewObj.class) return (T)(NewObj<?>)() -> refl(ctor::newInstance);
			if(functionalInterface == NewObj.Instance.class){
				//noinspection rawtypes
				return (T)(NewObj.Instance)() -> refl(() -> (IOInstance<?>)ctor.newInstance());
			}
			if(functionalInterface == Function.class) return (T)(Function<?, ?>)o -> refl(() -> ctor.newInstance(o));
			if(functionalInterface == IntFunction.class) return (T)(IntFunction<?>)o -> refl(() -> ctor.newInstance(o));
			if(functionalInterface == LongFunction.class) return (T)(LongFunction<?>)o -> refl(() -> ctor.newInstance(o));
			if(functionalInterface == BiFunction.class) return (T)(BiFunction<?, ?, ?>)(a, b) -> refl(() -> ctor.newInstance(a, b));
			if(functionalInterface == TriFunction.class) return (T)(TriFunction<?, ?, ?, ?>)(a, b, c) -> refl(() -> ctor.newInstance(a, b, c));
			
			throw new UnsupportedOperationException();
		}
	}
	
	abstract class LookupAccessProvider implements AccessProvider{
		
		protected abstract MethodHandles.Lookup getLookup(Class<?> clazz, Mode... modes) throws Defunct, IllegalAccessException;
		
		@Override
		public VarHandle unreflect(Field field) throws IllegalAccessException, Defunct{
			var requiredMode = AccessUtils.modeFromModifiers(field.getModifiers());
			
			var lookup = getLookup(field.getDeclaringClass(), requiredMode);
			return lookup.unreflectVarHandle(field);
		}
		
		@Override
		public MethodHandle unreflect(Method method) throws IllegalAccessException, Defunct{
			var mode   = AccessUtils.modeFromModifiers(method.getModifiers());
			var lookup = getLookup(method.getDeclaringClass(), mode);
			return lookup.unreflect(method);
		}
		@Override
		public MethodHandle unreflect(Constructor<?> constructor) throws IllegalAccessException, Defunct{
			var mode   = AccessUtils.modeFromModifiers(constructor.getModifiers());
			var lookup = getLookup(constructor.getDeclaringClass(), mode);
			return lookup.unreflectConstructor(constructor);
		}
		
		@Override
		public <InterfType, T extends InterfType> T makeLambda(Method method, Class<InterfType> functionalInterface)
			throws IllegalAccessException, Defunct, LambdaConversionException{
			var lookup = getLookup(method.getDeclaringClass(), Mode.PRIVATE, Mode.MODULE);
			return createFromCallSite(functionalInterface, lookup, lookup.unreflect(method));
		}
		@Override
		public <InterfType, T extends InterfType> T makeLambda(Constructor<?> constructor, Class<InterfType> functionalInterface)
			throws IllegalAccessException, Defunct, LambdaConversionException{
			var lookup = getLookup(constructor.getDeclaringClass(), Mode.PRIVATE, Mode.MODULE);
			return createFromCallSite(functionalInterface, lookup, lookup.unreflectConstructor(constructor));
		}
		@Override
		public Class<?> defineClass(Class<?> target, byte[] bytecode) throws IllegalAccessException, Defunct{
			var lookup = getLookup(target, Mode.PACKAGE);
			return lookup.defineClass(bytecode);
		}
		
		
		private static <InterfType, T extends InterfType>
		T createFromCallSite(Class<InterfType> functionalInterface, MethodHandles.Lookup lookup, MethodHandle handle) throws LambdaConversionException{
			var    site   = createCallSite(functionalInterface, lookup, handle);
			var    target = site.getTarget();
			Object interf;
			try{
				interf = target.invoke();
			}catch(Throwable e){
				throw new RuntimeException("Failed to create lambda", e);
			}
			//noinspection unchecked
			return Objects.requireNonNull((T)interf);
		}
		
		private static CallSite createCallSite(Class<?> functionalInterface, MethodHandles.Lookup lookup, MethodHandle handle) throws LambdaConversionException{
			Method     lambdaFunction = getFunctionalMethod(functionalInterface);
			MethodType signature      = MethodType.methodType(lambdaFunction.getReturnType(), lambdaFunction.getParameterTypes());
			
			var funName    = lambdaFunction.getName();
			var funType    = MethodType.methodType(functionalInterface);
			var handleType = handle.type();
			
			return LambdaMetafactory.metafactory(lookup, funName, funType, signature, handle, handleType);
		}
		private static Method getFunctionalMethod(Class<?> functionalInterface){
			var methods = Iters.from(functionalInterface.getMethods())
			                   .filter(m -> !Modifier.isStatic(m.getModifiers()) && Modifier.isAbstract(m.getModifiers()))
			                   .toModList();
			if(methods.size() != 1){
				throw new IllegalArgumentException(functionalInterface + " is not a functional interface!");
			}
			return methods.getFirst();
		}
	}
	
	class WeaklyProvidedLookup extends LookupAccessProvider{
		
		private final String                              lookupProviderPath;
		private final WeakReference<ClassLoader>          classLoader;
		private       WeakReference<MethodHandles.Lookup> lookupRef;
		
		public WeaklyProvidedLookup(Class<?> lookupProvider){
			if(lookupProvider.isHidden()){
				throw new IllegalArgumentException(Log.fmt("{}#red can not be hidden (is lambda?)", lookupProvider.getName()));
			}
			if(!UtilL.instanceOf(lookupProvider, Supplier.class)){
				throw new IllegalArgumentException(Log.fmt("{}#red must be a Supplier<MethodHandles.Lookup>", lookupProvider.getName()));
			}
			if(Iters.from(lookupProvider.getConstructors()).noneMatch(c -> c.getParameterCount() == 0)){
				throw new IllegalArgumentException(Log.fmt("{}#red must have an empty constructor", lookupProvider.getName()));
			}
			
			
			classLoader = new WeakReference<>(lookupProvider.getClassLoader());
			lookupProviderPath = lookupProvider.getName();
			
			try{
				var loaded = loadClass();
				if(loaded != lookupProvider){
					throw new RuntimeException(Log.fmt(
						"""
							Class not the same as loaded!
							  Provided: {}#orange
							  Loaded:   {}#red""",
						lookupProvider, loaded
					));
				}
				
				lookupRef = new WeakReference<>(findLookup());
			}catch(Defunct e){
				throw new ShouldNeverHappenError(e);
			}
		}
		
		@Override
		protected MethodHandles.Lookup getLookup(Class<?> clazz, Mode... modes) throws Defunct, IllegalAccessException{
			if(AccessUtils.isPublicMode(modes)) return MethodHandles.publicLookup();
			var lookup = getLookup();
			return AccessUtils.adaptLookupTo(lookup, clazz, modes);
		}
		
		private MethodHandles.Lookup getLookup() throws Defunct{
			var cached = lookupRef.get();
			if(cached != null) return cached;
			
			var lkup = findLookup();
			lookupRef = new WeakReference<>(lkup);
			return lkup;
		}
		
		private MethodHandles.Lookup findLookup() throws Defunct{
			var clazz = loadClass();
			
			Constructor<Supplier<MethodHandles.Lookup>> ctor;
			try{
				ctor = clazz.getConstructor();
			}catch(NoSuchMethodException e){
				throw new ShouldNeverHappenError(e);
			}
			Supplier<MethodHandles.Lookup> supplier;
			try{
				supplier = ctor.newInstance();
			}catch(InstantiationException|IllegalAccessException e){
				throw new ShouldNeverHappenError(e);
			}catch(InvocationTargetException e){
				throw UtilL.uncheckedThrow(e.getCause() == null? e : e.getCause());
			}
			
			var lookup = supplier.get();
			if(lookup == null){
				throw new IllegalStateException(Log.fmt("{}#red should never return null!", clazz.getName()));
			}
			return lookup;
		}
		
		private Class<Supplier<MethodHandles.Lookup>> loadClass() throws Defunct{
			var classLoader = this.classLoader.get();
			if(classLoader == null){
				throw new Defunct();
			}
			try{
				var cl = classLoader.loadClass(lookupProviderPath);
				//noinspection unchecked
				return (Class<Supplier<MethodHandles.Lookup>>)cl;
			}catch(ClassNotFoundException e){
				throw new RuntimeException(e);
			}
		}
	}
	
	class DirectLookup extends LookupAccessProvider{
		
		private final MethodHandles.Lookup lookup;
		
		public DirectLookup(MethodHandles.Lookup lookup){ this.lookup = Objects.requireNonNull(lookup); }
		
		@Override
		protected MethodHandles.Lookup getLookup(Class<?> clazz, Mode... modes) throws IllegalAccessException{
			if(AccessUtils.isPublicMode(modes)) return MethodHandles.publicLookup();
			return AccessUtils.adaptLookupTo(lookup, clazz, modes);
		}
	}
	
	VarHandle unreflect(Field field) throws IllegalAccessException, Defunct;
	MethodHandle unreflect(Method method) throws IllegalAccessException, Defunct;
	MethodHandle unreflect(Constructor<?> constructor) throws IllegalAccessException, Defunct;
	
	<InterfType, T extends InterfType> T makeLambda(Method method, Class<InterfType> functionalInterface)
		throws IllegalAccessException, Defunct, LambdaConversionException;
	<InterfType, T extends InterfType> T makeLambda(Constructor<?> constructor, Class<InterfType> functionalInterface)
		throws IllegalAccessException, Defunct, LambdaConversionException;
	
	Class<?> defineClass(Class<?> target, byte[] bytecode) throws IllegalAccessException, Defunct;
}
