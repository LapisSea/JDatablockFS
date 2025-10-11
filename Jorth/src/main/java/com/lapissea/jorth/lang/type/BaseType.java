package com.lapissea.jorth.lang.type;

import com.lapissea.jorth.lang.ClassName;

import static org.objectweb.asm.Opcodes.*;

public enum BaseType{
	//@formatter:off
		OBJ    ("O", Object.class,  ARETURN, ALOAD, ASTORE, 1, false, AASTORE, AALOAD),
		VOID   ("V", void.class,    RETURN,  -1,    -1, 0, false, -1,-1),
		CHAR   ("C", char.class,    IRETURN, ILOAD, ISTORE, 1, false, CASTORE, CALOAD),
		BYTE   ("B", byte.class,    IRETURN, ILOAD, ISTORE, 1, true,  BASTORE, BALOAD),
		SHORT  ("S", short.class,   IRETURN, ILOAD, ISTORE, 1, true,  SASTORE, SALOAD),
		INT    ("I", int.class,     IRETURN, ILOAD, ISTORE, 1, true,  IASTORE, IALOAD),
		LONG   ("J", long.class,    LRETURN, LLOAD, LSTORE, 2, false, LASTORE, LALOAD),
		FLOAT  ("F", float.class,   FRETURN, FLOAD, FSTORE, 1, false, FASTORE, FALOAD),
		DOUBLE ("D", double.class,  DRETURN, DLOAD, DSTORE, 2, false, DASTORE, DALOAD),
		BOOLEAN("Z", boolean.class, IRETURN, ILOAD, ISTORE, 1, false, BASTORE, BALOAD)
	;
	//@formatter:on
	
	public final String   jvmStr;
	public final Class<?> type;
	public final int      returnOp;
	public final int      loadOp;
	public final int      storeOp;
	public final int      slots;
	public final boolean  arrayIndexCompatible;
	public final int      arrayStoreOP;
	public final int      arrayLoadOP;
	
	BaseType(String jvmStr, Class<?> type, int returnOp, int loadOp, int storeOp, int slots, boolean arrayIndexCompatible, int arrayStoreOP, int arrayLoadOP){
		this.jvmStr = jvmStr;
		this.type = type;
		this.returnOp = returnOp;
		this.loadOp = loadOp;
		this.storeOp = storeOp;
		this.slots = slots;
		this.arrayIndexCompatible = arrayIndexCompatible;
		this.arrayStoreOP = arrayStoreOP;
		this.arrayLoadOP = arrayLoadOP;
	}
	
	private static final KeyedEnum.Lookup<BaseType> PRIMITIVES = KeyedEnum.getLookup(BaseType.class).excluding(OBJ, VOID);
	
	public static BaseType of(ClassName type){ return of(type.any()); }
	public static BaseType of(String name){
		var p = ofPrimitive(name);
		return p == null? OBJ : p;
	}
	public static BaseType ofPrimitive(ClassName type){ return ofPrimitive(type.any()); }
	public static BaseType ofPrimitive(String name){
		return PRIMITIVES.getOptional(name);
	}
	
	public String jvmStr(){
		return jvmStr;
	}
	public Class<?> type(){
		return type;
	}
}
