package com.lapissea.dfs.type.field.access;

import com.lapissea.util.ShouldNeverHappenError;

public final class TypeFlag{
	
	public static final int ID_OBJECT  = 0;
	public static final int ID_LONG    = 1;
	public static final int ID_INT     = 2;
	public static final int ID_BOOLEAN = 3;
	public static final int ID_FLOAT   = 4;
	public static final int ID_DOUBLE  = 5;
	public static final int ID_SHORT   = 6;
	public static final int ID_CHAR    = 7;
	public static final int ID_BYTE    = 8;
	
	public static int getId(Class<?> clazz){
		if(clazz.isPrimitive()){
			if(clazz == double.class) return ID_DOUBLE;
			if(clazz == float.class) return ID_FLOAT;
			if(clazz == byte.class) return ID_BYTE;
			if(clazz == boolean.class) return ID_BOOLEAN;
			if(clazz == long.class) return ID_LONG;
			if(clazz == int.class) return ID_INT;
			if(clazz == short.class) return ID_SHORT;
			if(clazz == char.class) return ID_CHAR;
			throw new ShouldNeverHappenError();
		}
		return ID_OBJECT;
	}
	
}
