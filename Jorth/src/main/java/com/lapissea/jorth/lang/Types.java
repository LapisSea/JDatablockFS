package com.lapissea.jorth.lang;

import static org.objectweb.asm.Opcodes.*;

public enum Types{
	
	VOID  (RETURN ),
	OBJECT(ARETURN),
	CHAR  (IRETURN),
	FLOAT (FRETURN),
	DOUBLE(DRETURN),
	BYTE  (IRETURN),
	SHORT (IRETURN),
	INT   (IRETURN),
	LONG  (LRETURN),
	BOOL  (IRETURN);
	
	public final int returnOp;
	public final String lower;
	
	Types(int returnOp){
		this.returnOp=returnOp;
		lower=name().toLowerCase();
	}
}
