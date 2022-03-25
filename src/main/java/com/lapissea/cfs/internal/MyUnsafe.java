package com.lapissea.cfs.internal;

import sun.misc.Unsafe;

import java.lang.reflect.Constructor;

public class MyUnsafe{
	
	static final Unsafe UNSAFE;
	
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
}
