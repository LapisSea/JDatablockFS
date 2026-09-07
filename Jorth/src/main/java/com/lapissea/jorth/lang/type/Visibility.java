package com.lapissea.jorth.lang.type;

import java.lang.reflect.Modifier;

import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ACC_PROTECTED;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;

public enum Visibility{
	PRIVATE(ACC_PRIVATE),
	PACKAGE_PRIVATE(0),
	PROTECTED(ACC_PROTECTED),
	PUBLIC(ACC_PUBLIC),
	;
	
	public static Visibility ofModifiers(int modifiers){
		if(Modifier.isPublic(modifiers)) return PUBLIC;
		if(Modifier.isProtected(modifiers)) return PROTECTED;
		if(Modifier.isPrivate(modifiers)) return PRIVATE;
		return PACKAGE_PRIVATE;
	}

	public final int flag;
	
	Visibility(int flag){
		this.flag = flag;
	}
	
}
