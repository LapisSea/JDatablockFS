package com.lapissea.dfs.internal;

import com.lapissea.dfs.internal.Access.Mode;
import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.NewObj;
import com.lapissea.iterableplus.Iters;
import com.lapissea.util.NotImplementedException;
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
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.LongFunction;

public interface AccessProvider{
	
	class UnoptimizedAccessProvider implements AccessProvider{
		
		@Override
		public VarHandle unreflect(Field field){ throw new UnsupportedOperationException(); }
		@Override
		public MethodHandle unreflect(Method method){ throw new UnsupportedOperationException(); }
		@Override
		public MethodHandle unreflect(Constructor<?> constructor){ throw new UnsupportedOperationException(); }
		
		@Override
		public <InterfType, T extends InterfType> T makeLambda(Method method, Class<InterfType> functionalInterface, boolean require){
			throw NotImplementedException.infer();//TODO: implement UnoptimizedAccessProvider.makeLambda()
		}
		@Override
		public <InterfType, T extends InterfType> T makeLambda(Constructor<?> constructor, Class<InterfType> functionalInterface, boolean require){
			return makeLambdaUnop(constructor, functionalInterface);
		}
		
		@Override
		public Class<?> defineClass(Class<?> target, byte[] bytecode, boolean hidden){ throw new UnsupportedOperationException(); }
		@Override
		public AccessProvider adapt(Class<?> target, boolean required, Mode[] modes) throws IllegalAccessException{
			if(AccessUtils.isPublicMode(modes)) return this;
			var lookup = MethodHandles.privateLookupIn(target, MethodHandles.publicLookup());
			var err    = AccessUtils.requireModes(lookup, modes);
			if(err.isPresent()){
				throw new IllegalAccessException(err.get().get());
			}
			return new DirectLookup(lookup);
		}
		
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
	
	class DirectLookup implements AccessProvider{
		
		private final MethodHandles.Lookup lookup;
		
		public DirectLookup(MethodHandles.Lookup lookup){ this.lookup = Objects.requireNonNull(lookup); }
		
		private MethodHandles.Lookup getLookup(Class<?> clazz, boolean strip, boolean require, Mode... modes) throws IllegalAccessException{
			
			// Public members do not need the lookup class to be the target, so a public lookup can be used
			// directly. Forcing privateLookupIn here would break ordinary public access.
			if(AccessUtils.isPublicMode(modes) && AccessUtils.hasModes(lookup, modes)){
				return lookup;
			}
			
			var targetLookup = AccessUtils.adaptLookupTo(lookup, clazz);
			if(strip) targetLookup = AccessUtils.stripModes(targetLookup, modes);
			var err = AccessUtils.requireModes(targetLookup, modes);
			if(err.isPresent()){
				if(require){
					throw new IllegalAccessException(err.get().get());
				}
				return null;
			}
			return targetLookup;
		}
		
		@Override
		public VarHandle unreflect(Field field) throws IllegalAccessException{
			var requiredMode = AccessUtils.modeFromModifiers(field.getModifiers());
			
			var lookup = getLookup(field.getDeclaringClass(), false, true, requiredMode);
			return lookup.unreflectVarHandle(field);
		}
		
		@Override
		public MethodHandle unreflect(Method method) throws IllegalAccessException{
			var mode   = AccessUtils.modeFromModifiers(method.getModifiers());
			var lookup = getLookup(method.getDeclaringClass(), false, true, mode);
			return lookup.unreflect(method);
		}
		@Override
		public MethodHandle unreflect(Constructor<?> constructor) throws IllegalAccessException{
			var mode   = AccessUtils.modeFromModifiers(constructor.getModifiers());
			var lookup = getLookup(constructor.getDeclaringClass(), false, true, mode);
			return lookup.unreflectConstructor(constructor);
		}
		
		@Override
		public <InterfType, T extends InterfType> T makeLambda(Method method, Class<InterfType> functionalInterface, boolean require)
			throws IllegalAccessException{
			var lookup = getLookup(method.getDeclaringClass(), false, require, Mode.PRIVATE, Mode.MODULE);
			return lookup == null? null : createFromCallSite(functionalInterface, lookup, lookup.unreflect(method));
		}
		@Override
		public <InterfType, T extends InterfType> T makeLambda(Constructor<?> constructor, Class<InterfType> functionalInterface, boolean require)
			throws IllegalAccessException{
			var lookup = getLookup(constructor.getDeclaringClass(), false, require, Mode.PRIVATE, Mode.MODULE);
			return lookup == null? null : createFromCallSite(functionalInterface, lookup, lookup.unreflectConstructor(constructor));
		}
		@Override
		public Class<?> defineClass(Class<?> target, byte[] bytecode, boolean hidden) throws IllegalAccessException{
			if(hidden){
				var lookup = getLookup(target, true, true, Mode.PRIVATE, Mode.MODULE);
				return lookup.defineHiddenClass(bytecode, true, MethodHandles.Lookup.ClassOption.NESTMATE).lookupClass();
			}
			var lookup = getLookup(target, true, true, Mode.PACKAGE);
			return lookup.defineClass(bytecode);
		}
		
		@Override
		public Class<?> defineClassWithClassData(Class<?> target, byte[] bytecode, Object classData) throws IllegalAccessException{
			var lookup = getLookup(target, true, true, Mode.PRIVATE, Mode.MODULE);
			return lookup.defineHiddenClassWithClassData(bytecode, classData, true, MethodHandles.Lookup.ClassOption.NESTMATE).lookupClass();
		}

		@Override
		public AccessProvider adapt(Class<?> target, boolean required, Mode... modes) throws IllegalAccessException{
			var lookup = getLookup(target, true, required, modes);
			if(lookup == null) return null;
			var err = AccessUtils.requireModes(lookup, modes);
			if(err.isPresent()){
				throw new IllegalAccessException(err.get().get());
			}
			return new DirectLookup(lookup);
		}
		
		private static <InterfType, T extends InterfType>
		T createFromCallSite(Class<InterfType> functionalInterface, MethodHandles.Lookup lookup, MethodHandle handle) throws IllegalAccessException{
			CallSite     site   = createCallSite(functionalInterface, lookup, handle);
			MethodHandle target = site.getTarget();
			Object       interf;
			try{
				interf = target.invoke();
			}catch(Throwable e){
				throw new RuntimeException("Failed to create lambda", e);
			}
			//noinspection unchecked
			return Objects.requireNonNull((T)interf);
		}
		
		private static CallSite createCallSite(Class<?> functionalInterface, MethodHandles.Lookup caller, MethodHandle handle) throws IllegalAccessException{
			Method     lambdaFunction = getFunctionalMethod(functionalInterface);
			MethodType signature      = MethodType.methodType(lambdaFunction.getReturnType(), lambdaFunction.getParameterTypes());
			
			var funName    = lambdaFunction.getName();
			var funType    = MethodType.methodType(functionalInterface);
			var handleType = handle.type();
			
			if(!caller.hasFullPrivilegeAccess()){
				throw new IllegalAccessException(Log.fmt("Invalid caller: {}#red", caller));
			}
			try{
				return LambdaMetafactory.metafactory(caller, funName, funType, signature, handle, handleType);
			}catch(LambdaConversionException e){
				throw new IllegalArgumentException(Log.fmt("Could not create lambda factory from {}#red to {}#red", handle, functionalInterface), e);
			}
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
		
		@Override
		public String toString(){
			return "Direct{" + lookup + "}";
		}
	}
	
	VarHandle unreflect(Field field) throws IllegalAccessException;
	MethodHandle unreflect(Method method) throws IllegalAccessException;
	MethodHandle unreflect(Constructor<?> constructor) throws IllegalAccessException;
	
	<InterfType, T extends InterfType> T makeLambda(Method method, Class<InterfType> functionalInterface, boolean require)
		throws IllegalAccessException;
	<InterfType, T extends InterfType> T makeLambda(Constructor<?> constructor, Class<InterfType> functionalInterface, boolean require)
		throws IllegalAccessException;
	
	default <InterfType, T extends InterfType> T makeLambda(Method method, Class<InterfType> functionalInterface)
		throws IllegalAccessException{ return makeLambda(method, functionalInterface, true); }
	default <InterfType, T extends InterfType> T makeLambda(Constructor<?> constructor, Class<InterfType> functionalInterface)
		throws IllegalAccessException{ return makeLambda(constructor, functionalInterface, true); }
	
	default Class<?> defineClass(Class<?> target, byte[] bytecode) throws IllegalAccessException{
		return defineClass(target, bytecode, false);
	}
	Class<?> defineClass(Class<?> target, byte[] bytecode, boolean hidden) throws IllegalAccessException;
	default Class<?> defineClassWithClassData(Class<?> target, byte[] bytecode, Object classData) throws IllegalAccessException{
		throw new UnsupportedOperationException();
	}
	
	default AccessProvider adapt(Class<?> target, Mode... modes) throws IllegalAccessException{
		return adapt(target, true, modes);
	}
	
	AccessProvider adapt(Class<?> target, boolean require, Mode... modes) throws IllegalAccessException;
}
