package com.lapissea.cfs.internal;

import sun.misc.Unsafe;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.ByteOrder;
import java.util.OptionalLong;

public class MyUnsafe{
	
	public static final Unsafe UNSAFE;
	
	public static final  boolean IS_BIG_ENDIAN=ByteOrder.nativeOrder()==ByteOrder.BIG_ENDIAN;
	private static final boolean OFFSET_ENABLE=Runtime.version().feature()<=19;
	
	static{
		try{
			Constructor<Unsafe> unsafeConstructor=Unsafe.class.getDeclaredConstructor();
			unsafeConstructor.setAccessible(true);
			UNSAFE=unsafeConstructor.newInstance();
		}catch(ReflectiveOperationException e){
			e.printStackTrace();
			throw new RuntimeException(e);
		}
	}
	
	@SuppressWarnings("deprecation")
	public static OptionalLong objectFieldOffset(Field field){
		if(!OFFSET_ENABLE) return OptionalLong.empty();
		field.setAccessible(true);
		return OptionalLong.of(UNSAFE.objectFieldOffset(field));
	}
	
	public static int arrayStart(Class<?> type){
		return UNSAFE.arrayBaseOffset(type);
	}
}
