package com.lapissea.dfs.internal;

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
	
	public static final  Unsafe           UNSAFE;
	private static final Optional<Method> objectFieldOffset_METHOD;
	
	static{
		try{
			Constructor<Unsafe> unsafeConstructor = Unsafe.class.getDeclaredConstructor();
			unsafeConstructor.setAccessible(true);
			UNSAFE = unsafeConstructor.newInstance();
		}catch(ReflectiveOperationException e){
			e.printStackTrace();
			throw new RuntimeException(e);
		}
		
		Optional<Method> om;
		try{
			var m = Unsafe.class.getDeclaredMethod("objectFieldOffset", Field.class);
			m.setAccessible(true);
			om = Optional.of(m);
		}catch(Throwable e){
			om = Optional.empty();
		}
		
		if(om.isPresent()){
			Field off;
			try{
				off = OffsetCheck.class.getDeclaredField("field");
			}catch(NoSuchFieldException e){
				throw new ShouldNeverHappenError(e);
			}
			
			try{
				om.get().invoke(UNSAFE, off);
			}catch(Throwable e){
				om = Optional.empty();
			}
		}
		
		objectFieldOffset_METHOD = om;
	}
	
	public static boolean hasNoObjectFieldOffset(){
		return objectFieldOffset_METHOD.isEmpty();
	}
	public static long objectFieldOffset(Field field) throws IllegalAccessException{
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
