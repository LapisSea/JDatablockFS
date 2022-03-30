package com.lapissea.cfs.internal;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.exceptions.MalformedStructLayout;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.NotNull;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.UtilL;

import java.lang.invoke.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static com.lapissea.cfs.internal.MyUnsafe.UNSAFE;
import static java.lang.invoke.MethodHandles.Lookup.MODULE;
import static java.lang.invoke.MethodHandles.Lookup.PACKAGE;
import static java.lang.invoke.MethodHandles.Lookup.PRIVATE;
import static java.lang.invoke.MethodHandles.Lookup.PUBLIC;

@SuppressWarnings("unchecked")
public class Access{
	
	private static final boolean USE_UNSAFE_LOOKUP=true;
	
	private static final long ACCESS_OFFSET;
	
	static{
		@SuppressWarnings("all")
		class Mirror{
			Class<?> lookupClass;
			Class<?> prevLookupClass;
			int      allowedModes;
			
			volatile ProtectionDomain cachedProtectionDomain;
			
			Mirror(Class<?> lookupClass, Class<?> prevLookupClass, int allowedModes){
				this.lookupClass=lookupClass;
				this.prevLookupClass=prevLookupClass;
				this.allowedModes=allowedModes;
			}
		}
		
		int offset=0;
		var mirror=new Mirror(null, null, Integer.MAX_VALUE);
		while(true){
			int off=offset;
			
			var ok=IntStream.range(0, 100).map(i->i+1234).allMatch(i->{
				ByteBuffer bb=ByteBuffer.allocate(4);
				new Random(i).nextBytes(bb.array());
				mirror.allowedModes=bb.getInt(0);
				var val=UNSAFE.getInt(mirror, off);
				return mirror.allowedModes==val;
			});
			
			if(ok) break;
			
			offset++;
		}
		ACCESS_OFFSET=offset;
	}
	
	public static <FInter, T extends FInter> T makeLambda(Method method, Class<FInter> functionalInterface){
		Method functionalInterfaceFunction=null;
		try{
			var lookup=getLookup(method.getDeclaringClass(), method.getModifiers());
			method.setAccessible(true);
			var handle=lookup.unreflect(method);
			
			functionalInterfaceFunction=getFunctionalMethod(functionalInterface);
			
			MethodType signature=MethodType.methodType(functionalInterfaceFunction.getReturnType(), functionalInterfaceFunction.getParameterTypes());
			
			CallSite site=LambdaMetafactory.metafactory(lookup,
			                                            functionalInterfaceFunction.getName(),
			                                            MethodType.methodType(functionalInterface),
			                                            signature,
			                                            handle,
			                                            handle.type());
			
			return (T)site.getTarget().invoke();
		}catch(Throwable e){
			throw new RuntimeException("failed to create lambda\n"+method+"\n"+functionalInterface+(functionalInterfaceFunction!=null?" ("+functionalInterfaceFunction.getName()+")":""), e);
		}
	}
	
	public static <FInter> Method getFunctionalMethod(Class<FInter> functionalInterface){
		var methods=Arrays.stream(functionalInterface.getMethods()).filter(m->!Modifier.isStatic(m.getModifiers())&&Modifier.isAbstract(m.getModifiers())).toList();
		if(methods.size()!=1){
			throw new IllegalArgumentException(functionalInterface+" is not a functional interface!");
		}
		return methods.get(0);
	}
	
	public static <FInter, T extends FInter> T makeLambda(Constructor<?> constructor, Class<FInter> functionalInterface){
		try{
			var lookup=getLookup(constructor.getDeclaringClass(), constructor.getModifiers());
			constructor.setAccessible(true);
			var handle=lookup.unreflectConstructor(constructor);
			
			Method functionalInterfaceFunction=getFunctionalMethod(functionalInterface);
			
			MethodType signature=MethodType.methodType(functionalInterfaceFunction.getReturnType(), functionalInterfaceFunction.getParameterTypes());
			
			CallSite site;
			try{
				site=LambdaMetafactory.metafactory(lookup,
				                                   functionalInterfaceFunction.getName(),
				                                   MethodType.methodType(functionalInterface),
				                                   signature,
				                                   handle,
				                                   handle.type());
			}catch(LambdaConversionException e){
				if(USE_UNSAFE_LOOKUP) throw new ShouldNeverHappenError("Unsafe lookup should solve this", e);
				
				T val=tryRecoverWithOld(constructor, functionalInterface);
				if(val!=null) return val;
				throw new NotImplementedException("java modules cause this not to be supported", e);
			}
			
			return Objects.requireNonNull((T)site.getTarget().invoke());
		}catch(Throwable e){
			throw new RuntimeException("failed to create lambda\n"+constructor+"\n"+functionalInterface, e);
		}
	}
	
	private static <FInter, T extends FInter> T tryRecoverWithOld(Constructor<?> constructor, Class<FInter> functionalInterface){
		if(functionalInterface==Supplier.class){
			return (T)(Supplier<Object>)()->{
				try{
					return constructor.newInstance();
				}catch(ReflectiveOperationException ex){
					throw new RuntimeException(ex);
				}
			};
		}
		return null;
	}
	
	private static MethodHandles.Lookup getLookup(Class<?> clazz, int modifiers) throws IllegalAccessException{
		allowModule(clazz);
		var local=MethodHandles.lookup();
		if(USE_UNSAFE_LOOKUP){
			local=MethodHandles.privateLookupIn(clazz, local);
			corruptPermissions(local);
			return local;
		}else{
			if(Modifier.isPublic(clazz.getModifiers())&&Modifier.isPublic(modifiers)) return local;
			return MethodHandles.privateLookupIn(clazz, local);
		}
	}
	
	private static void corruptPermissions(MethodHandles.Lookup lookup){
		if(lookup.hasFullPrivilegeAccess()) return;
		
		//Ensure only intended/relevant lookup is corrupted
		checkTarget:
		{
			var cls=lookup.lookupClass();
			
			for(var consentClass : List.of(IOInstance.class, StructPipe.class)){
				if(UtilL.instanceOf(cls, consentClass)){
					break checkTarget;
				}
			}
			
			throw new SecurityException("Unsafe attempt of lookup modification: "+lookup);
		}
		
		UNSAFE.getAndSetInt(lookup, ACCESS_OFFSET, PUBLIC|MODULE|PACKAGE|PRIVATE);
		if(!lookup.hasFullPrivilegeAccess()){
			throw new ShouldNeverHappenError();
		}
	}
	
	private static void allowModule(Class<?> clazz){
		var classModule=clazz.getModule();
		var thisModule =Utils.class.getModule();
		if(!thisModule.canRead(classModule)){
			thisModule.addReads(classModule);
			thisModule.addOpens(Utils.class.getPackageName(), classModule);
		}
	}
	
	public static VarHandle makeVarHandle(Field field){
		try{
			var lookup=getLookup(field.getDeclaringClass(), field.getModifiers());
			field.setAccessible(true);
			return lookup.findVarHandle(field.getDeclaringClass(), field.getName(), field.getType());
		}catch(Throwable e){
			throw new RuntimeException("failed to create VarHandle\n"+field, e);
		}
	}
	
	public static MethodHandle makeMethodHandle(@NotNull Method method){
		try{
			var lookup=getLookup(method.getDeclaringClass(), method.getModifiers());
			method.setAccessible(true);
			return lookup.unreflect(method);
		}catch(Throwable e){
			throw new RuntimeException("failed to create MethodHandle\n"+method, e);
		}
	}
	
	@NotNull
	public static <FInter, T extends FInter> T findConstructor(@NotNull Class<?> clazz, Class<FInter> functionalInterface, Class<?>... parameterTypes){
		try{
			Constructor<?> lconst;
			if(Modifier.isPrivate(clazz.getModifiers())){
				lconst=clazz.getDeclaredConstructor(parameterTypes);
			}else{
				lconst=clazz.getConstructor(parameterTypes);
			}
			
			return makeLambda(lconst, functionalInterface);
		}catch(ReflectiveOperationException ce){
			
			try{
				Method of=clazz.getMethod("of", parameterTypes);
				if(!Modifier.isStatic(of.getModifiers())) throw new ReflectiveOperationException(of+" not static");
				if(!Modifier.isPublic(of.getModifiers())) throw new ReflectiveOperationException(of+" not public");
				if(!of.getReturnType().equals(clazz)) throw new ReflectiveOperationException(of+" does not return "+clazz);
				
				return makeLambda(of, functionalInterface);
			}catch(ReflectiveOperationException ofe){
				throw new MalformedStructLayout(clazz.getName()+" does not have a valid constructor or of static method with arguments of "+Arrays.toString(parameterTypes));
			}
		}
	}
}
