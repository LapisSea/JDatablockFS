package com.lapissea.jorth.lang;

import static org.objectweb.asm.Opcodes.*;

public enum CallType{
	
	VIRTUAL(INVOKEVIRTUAL),
	STATIC(INVOKESTATIC),
	SPECIAL(INVOKESPECIAL);
	
	public final int op;
	
	CallType(int op){
		this.op=op;
	}
}
