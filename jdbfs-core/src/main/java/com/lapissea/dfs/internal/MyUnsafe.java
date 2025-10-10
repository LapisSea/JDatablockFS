package com.lapissea.dfs.internal;

import com.lapissea.dfs.logging.Log;
import com.lapissea.util.ShouldNeverHappenError;
import sun.misc.Unsafe;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;

public final class MyUnsafe{
	
	static class OffsetCheck{
		int field;
	}
	
	public static final Unsafe           UNSAFE;
	private static      Optional<Method> objectFieldOffset_METHOD;
	
	static{
		try{
			Constructor<Unsafe> unsafeConstructor = Unsafe.class.getDeclaredConstructor();
			unsafeConstructor.setAccessible(true);
			UNSAFE = unsafeConstructor.newInstance();
		}catch(ReflectiveOperationException e){
			e.printStackTrace();
			throw new RuntimeException(e);
		}
	}
	
	private static Optional<Method> objectFieldOffset_METHOD(){
		//noinspection OptionalAssignedToNull
		if(objectFieldOffset_METHOD != null) return objectFieldOffset_METHOD;
		
		Optional<Method> om;
		try{
			var m = Unsafe.class.getDeclaredMethod("objectFieldOffset", Field.class);
			m.setAccessible(true);
			om = Optional.of(m);
		}catch(Throwable e){
			om = Optional.empty();
		}
		
		int lastCheckedJVMVersion = 25;
		if(Runtime.version().feature()>lastCheckedJVMVersion && om.isPresent()){
			Field dummyField;
			try{
				dummyField = OffsetCheck.class.getDeclaredField("field");
			}catch(NoSuchFieldException e){
				throw new ShouldNeverHappenError(e);
			}
			
			try{
				var res = (long)om.get().invoke(UNSAFE, dummyField);
				if(res<=0){
					Log.trace("Unsafe#objectFieldOffset exists but may be a noop. Disabling unsafe access.");
					om = Optional.empty();
				}
			}catch(Throwable e){
				Log.trace("Unsafe#objectFieldOffset exists but is failing. Disabling unsafe access.");
				om = Optional.empty();
			}
		}
		return objectFieldOffset_METHOD = om;
	}
	
	public static boolean hasNoObjectFieldOffset(){
		return objectFieldOffset_METHOD().isEmpty();
	}
	public static long objectFieldOffset(Field field) throws IllegalAccessException{
		var objectFieldOffset_METHOD = objectFieldOffset_METHOD();
		if(objectFieldOffset_METHOD.isEmpty()){
			throw new IllegalAccessException("No objectFieldOffset");
		}
		try{
			return (long)objectFieldOffset_METHOD.get().invoke(UNSAFE, field);
		}catch(InvocationTargetException e){
			if(e.getCause() instanceof UnsupportedOperationException err){
				throw new IllegalAccessException("Unsupported filed offset: " + err);
			}
			throw new RuntimeException(e);
		}
	}
}
