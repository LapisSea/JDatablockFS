package com.lapissea.jorth.lang.type;

import com.lapissea.jorth.lang.ClassName;

import static org.objectweb.asm.Opcodes.*;

public enum BaseType{
	//@formatter:off
		OBJ    ("O", Object.class,  ARETURN, ALOAD, ASTORE, 1, false, AASTORE, AALOAD, IF_ACMPEQ, IF_ACMPNE, -1),
		VOID   ("V", void.class,    RETURN,  -1,    -1, 0, false, -1,-1, -1, -1, -1),
		CHAR   ("C", char.class,    IRETURN, ILOAD, ISTORE, 1, false, CASTORE, CALOAD, IF_ICMPEQ, IF_ICMPNE, -1),
		BYTE   ("B", byte.class,    IRETURN, ILOAD, ISTORE, 1, true,  BASTORE, BALOAD, IF_ICMPEQ, IF_ICMPNE, -1),
		SHORT  ("S", short.class,   IRETURN, ILOAD, ISTORE, 1, true,  SASTORE, SALOAD, IF_ICMPEQ, IF_ICMPNE, -1),
		INT    ("I", int.class,     IRETURN, ILOAD, ISTORE, 1, true,  IASTORE, IALOAD, IF_ICMPEQ, IF_ICMPNE, -1),
		LONG   ("J", long.class,    LRETURN, LLOAD, LSTORE, 2, false, LASTORE, LALOAD, IF_ICMPEQ, IF_ICMPNE, LCMP),
		FLOAT  ("F", float.class,   FRETURN, FLOAD, FSTORE, 1, false, FASTORE, FALOAD, IF_ICMPEQ, IF_ICMPNE, FCMPL),
		DOUBLE ("D", double.class,  DRETURN, DLOAD, DSTORE, 2, false, DASTORE, DALOAD, IF_ICMPEQ, IF_ICMPNE, DCMPL),
		BOOLEAN("Z", boolean.class, IRETURN, ILOAD, ISTORE, 1, false, BASTORE, BALOAD, IF_ICMPEQ, IF_ICMPNE, -1)
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
	public final int      eqJumpOp;
	public final int      neJumpOp;
	public final int      cmpOp;
	
	BaseType(String jvmStr, Class<?> type, int returnOp, int loadOp, int storeOp, int slots, boolean arrayIndexCompatible, int arrayStoreOP, int arrayLoadOP, int eqJumpOp, int neJumpOp, int cmpOp){
		this.jvmStr = jvmStr;
		this.type = type;
		this.returnOp = returnOp;
		this.loadOp = loadOp;
		this.storeOp = storeOp;
		this.slots = slots;
		this.arrayIndexCompatible = arrayIndexCompatible;
		this.arrayStoreOP = arrayStoreOP;
		this.arrayLoadOP = arrayLoadOP;
		this.eqJumpOp = eqJumpOp;
		this.neJumpOp = neJumpOp;
		this.cmpOp = cmpOp;
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
