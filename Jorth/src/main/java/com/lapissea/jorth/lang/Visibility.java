package com.lapissea.jorth.lang;

import com.lapissea.jorth.MalformedJorthException;

import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ACC_PROTECTED;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;

public enum Visibility{
	PUBLIC(ACC_PUBLIC),
	PRIVATE(ACC_PRIVATE),
	PROTECTED(ACC_PROTECTED);
	
	public final int    opCode;
	public final String lower;
	
	Visibility(int code){
		this.opCode=code;
		lower=name().toLowerCase();
	}
	
	public static Visibility fromName(String name) throws MalformedJorthException{
		var lower=name.toLowerCase();
		for(Visibility value : values()){
			if(lower.equals(value.lower)){
				return value;
			}
		}
		throw new MalformedJorthException("Unknown visibility "+name);
	}
}
