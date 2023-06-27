package com.lapissea.cfs.internal;

import sun.misc.Unsafe;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.ByteOrder;

public class MyUnsafe{
	
	public static final Unsafe UNSAFE;
	
	public static final boolean IS_BIG_ENDIAN = ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN;
	
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
	
	@SuppressWarnings("deprecation")
	public static long objectFieldOffset(Field field){
		return UNSAFE.objectFieldOffset(field);
	}
}
