package com.lapissea.jorth.redo;

import com.lapissea.jorth.exceptions.IllegalClassState;

import static org.objectweb.asm.Opcodes.ACC_ABSTRACT;
import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_STATIC;

public record AccessSet(boolean isAbstract, boolean isStatic, boolean isFinal){
	public AccessSet{
		if(isAbstract && isFinal){
			throw new IllegalClassState("Can not be both abstract and final");
		}
	}
	
	public static final AccessSet DEFAULT  = new AccessSet(false, false, false);
	public static final AccessSet ABSTRACT = new AccessSet(true, false, false);
	public static final AccessSet STATIC   = new AccessSet(false, true, false);
	public static final AccessSet FINAL    = new AccessSet(false, false, true);
	
	public AccessSet withoutAbstr(){
		return new AccessSet(false, isStatic, isFinal);
	}
	public AccessSet andAbstr(){
		return new AccessSet(true, isStatic, isFinal);
	}
	public AccessSet andStat(){
		return new AccessSet(isAbstract, true, isFinal);
	}
	public AccessSet andFin(){
		return new AccessSet(isAbstract, isStatic, true);
	}
	public AccessSet join(AccessSet other){
		return new AccessSet(
			isAbstract || other.isAbstract,
			isStatic || other.isStatic,
			isFinal || other.isFinal
		);
	}
	
	public int flags(){
		int res = 0;
		if(isAbstract) res |= ACC_ABSTRACT;
		if(isStatic) res |= ACC_STATIC;
		if(isFinal) res |= ACC_FINAL;
		return res;
	}
}
