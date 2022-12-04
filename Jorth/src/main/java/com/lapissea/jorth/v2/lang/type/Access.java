package com.lapissea.jorth.v2.lang.type;

import static org.objectweb.asm.Opcodes.ACC_ABSTRACT;
import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_STATIC;

public enum Access{
	ABSTRACT(ACC_ABSTRACT),
	STATIC(ACC_STATIC),
	FINAL(ACC_FINAL),
	;
	
	public final int flag;
	
	Access(int flag){
		this.flag=flag;
	}
	
}
