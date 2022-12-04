package com.lapissea.jorth.lang;

import java.util.List;

import static org.objectweb.asm.Opcodes.*;

public enum Types{
	
	VOID(RETURN, -1, 1, void.class),
	OBJECT(ARETURN, ALOAD, 1, Object.class),
	CHAR(IRETURN, ILOAD, 1, char.class),
	FLOAT(FRETURN, FLOAD, 1, float.class),
	DOUBLE(DRETURN, DLOAD, 2, double.class),
	BYTE(IRETURN, ILOAD, 1, byte.class),
	SHORT(IRETURN, ILOAD, 1, short.class),
	INT(IRETURN, ILOAD, 1, int.class),
	LONG(LRETURN, LLOAD, 2, long.class),
	BOOLEAN(IRETURN, ILOAD, 1, boolean.class);
	
	public final int      returnOp;
	public final int      loadOp;
	public final int      slotCount;
	public final Class<?> baseClass;
	public final String   lower;
	public final GenType  genTyp;
	
	Types(int returnOp, int loadOp, int slotCount, Class<?> baseClass){
		this.returnOp = returnOp;
		this.loadOp = loadOp;
		this.slotCount = slotCount;
		this.baseClass = baseClass;
		lower = name().toLowerCase();
		genTyp = new GenType(baseClass.getName(), 0, List.of(), this);
	}
	
}
