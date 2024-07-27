package com.lapissea.dfs.internal;

import com.lapissea.dfs.Utils;
import com.lapissea.dfs.config.ConfigDefs;
import com.lapissea.dfs.exceptions.MissingConstruct;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.NotNull;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.UtilL;

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
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

import static com.lapissea.dfs.internal.MyUnsafe.UNSAFE;
import static java.lang.invoke.MethodHandles.Lookup.*;

@SuppressWarnings("unchecked")
public final class Access{
	
	private static final boolean USE_UNSAFE_LOOKUP = ConfigDefs.USE_UNSAFE_LOOKUP.resolveValLocking();
	
	private static long calcModesOffset(){
		@SuppressWarnings("all")
		final class Mirror{
			Class<?> lookupClass;
			Class<?> prevLookupClass;
			int      allowedModes;
			
			volatile ProtectionDomain cachedProtectionDomain;
			
			Mirror(Class<?> lookupClass, Class<?> prevLookupClass, int allowedModes){
				this.lookupClass = lookupClass;
				this.prevLookupClass = prevLookupClass;
				this.allowedModes = allowedModes;
			}
		}
		
		long offset;
		try{
			offset = MyUnsafe.objectFieldOffset(Mirror.class.getDeclaredField("allowedModes"));
		}catch(NoSuchFieldException e){
			throw new RuntimeException(e);
		}
		return offset;
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
			var lookup = getLookup(method.getDeclaringClass());
			return createFromCallSite(functionalInterface, lookup, lookup.unreflect(method));
		}catch(Throwable e){
			throw new RuntimeException("failed to create lambda for method " + method + " with " + functionalInterface, e);
		}
	}
	
	public static <FInter, T extends FInter> T makeLambda(Constructor<?> constructor, Class<FInter> functionalInterface){
		try{
			constructor.setAccessible(true);
			var lookup = getLookup(constructor.getDeclaringClass());
			return createFromCallSite(functionalInterface, lookup, lookup.unreflectConstructor(constructor));
		}catch(Throwable e){
			throw new RuntimeException("failed to create lambda for constructor " + constructor + " with " + functionalInterface, e);
		}
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
	
	public static MethodHandles.Lookup privateLookupIn(Class<?> clazz) throws IllegalAccessException{
		try{
			return MethodHandles.privateLookupIn(clazz, MethodHandles.lookup());
		}catch(IllegalAccessException e){
			var tc         = Utils.getCallee(0);
			var thisModule = tc.getModule().getName();
			if(!e.getMessage().contains(thisModule + " does not read ")){
				throw e;
			}
			allowModule(clazz);
			return MethodHandles.privateLookupIn(clazz, MethodHandles.lookup());
		}
	}
	
	private static MethodHandles.Lookup getLookup(Class<?> clazz) throws IllegalAccessException{
		var lookup = privateLookupIn(clazz);
		if(lookup.hasFullPrivilegeAccess()){
			return lookup;
		}
		
		var local = MethodHandles.lookup();
		if(USE_UNSAFE_LOOKUP){
			local = MethodHandles.privateLookupIn(clazz, local);
			corruptPermissions(local);
			return local;
		}else{
			throw new NotImplementedException("Ask for consent not implemented");//TODO implement consent
		}
	}
	
	private static void corruptPermissions(MethodHandles.Lookup lookup){
		int allModes = PUBLIC|PRIVATE|PROTECTED|PACKAGE|MODULE|UNCONDITIONAL|ORIGINAL;
		
		if(lookup.lookupModes() == allModes){
			return;
		}
		
		//Ensure only intended/relevant lookup is corrupted
		checkTarget:
		{
			var cls = lookup.lookupClass();
			
			for(var consentClass : List.of(IOInstance.class, StructPipe.class, ChunkPointer.class)){
				if(UtilL.instanceOf(cls, consentClass)){
					break checkTarget;
				}
			}
			
			throw new SecurityException("Unsafe attempt of lookup modification: " + lookup);
		}
		
		//calculate objectFieldOffset every time as JVM may not keep a constant for a field
		long offset = calcModesOffset();
		
		UNSAFE.getAndSetInt(lookup, offset, allModes);
		if(lookup.lookupModes() != allModes){
			throw new ShouldNeverHappenError();
		}
		if(!lookup.hasFullPrivilegeAccess()){
			throw new ShouldNeverHappenError();
		}
	}
	
	private static void allowModule(Class<?> clazz){
		var classModule = clazz.getModule();
		var tc          = Utils.getCallee(0);
		var thisModule  = tc.getModule();
		if(!thisModule.canRead(classModule)){
			thisModule.addReads(classModule);
			thisModule.addOpens(tc.getPackageName(), classModule);
		}
	}
	
	public static VarHandle makeVarHandle(Field field){
		try{
			var lookup = getLookup(field.getDeclaringClass());
			field.setAccessible(true);
			return lookup.unreflectVarHandle(field);
		}catch(Throwable e){
			throw new RuntimeException("failed to create VarHandle\n" + field, e);
		}
	}
	
	public static MethodHandle makeMethodHandle(@NotNull Constructor<?> method){
		try{
			var lookup = privateLookupIn(method.getDeclaringClass());
			method.setAccessible(true);
			return lookup.unreflectConstructor(method);
		}catch(Throwable e){
			throw new RuntimeException("failed to create MethodHandle\n" + method, e);
		}
	}
	public static MethodHandle makeMethodHandle(@NotNull Method method){
		try{
			var lookup = privateLookupIn(method.getDeclaringClass());
			method.setAccessible(true);
			return lookup.unreflect(method);
		}catch(Throwable e){
			throw new RuntimeException("failed to create MethodHandle\n" + method, e);
		}
	}
	
	private static final Map<Class<?>, WeakReference<Class<?>[]>> ARG_CACHE = new WeakHashMap<>();
	
	public static <FInter, T extends FInter> T findConstructor(@NotNull Class<?> clazz, Class<FInter> functionalInterface){
		Class<?>[] args;
		typ:
		synchronized(ARG_CACHE){
			var ref = ARG_CACHE.get(functionalInterface);
			if(ref != null){
				args = ref.get();
				if(args != null) break typ;
			}
			args = Access.getFunctionalMethod(functionalInterface).getParameterTypes();
			ARG_CACHE.put(functionalInterface, new WeakReference<>(args));
		}
		
		return findConstructorArgs(clazz, functionalInterface, args);
	}
	
	@NotNull
	public static <FInter, T extends FInter> T findConstructorArgs(@NotNull Class<?> clazz, Class<FInter> functionalInterface, Class<?>... parameterTypes){
		try{
			Constructor<?> lconst;
			if(Modifier.isPrivate(clazz.getModifiers())){
				lconst = clazz.getDeclaredConstructor(parameterTypes);
			}else{
				lconst = clazz.getConstructor(parameterTypes);
			}
			
			return makeLambda(lconst, functionalInterface);
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
				var e = new MissingConstruct("fmt", "{}#red does not have a valid constructor or \"of\" static method with {}#yellow", clazz.getName(), argStr);
				e.addSuppressed(ce);
				e.addSuppressed(ofe);
				throw e;
			}
		}
	}
}
