package com.lapissea.jorth.lang.type;

import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ACC_PROTECTED;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;

public enum Visibility{
	PRIVATE(ACC_PRIVATE),
	PROTECTED(ACC_PROTECTED),
	PUBLIC(ACC_PUBLIC),
	;
	
	public final int flag;
	
	Visibility(int flag){
		this.flag = flag;
	}
	
}
