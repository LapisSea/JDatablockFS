package com.lapissea.jorth.lang.type;

import static org.objectweb.asm.Opcodes.ACC_ABSTRACT;
import static org.objectweb.asm.Opcodes.ACC_ENUM;
import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_STATIC;

public enum Access{
	ABSTRACT(ACC_ABSTRACT),
	STATIC(ACC_STATIC),
	FINAL(ACC_FINAL),
	ENUM(ACC_ENUM),
	;
	
	public final int flag;
	
	Access(int flag){
		this.flag = flag;
	}
	
}
