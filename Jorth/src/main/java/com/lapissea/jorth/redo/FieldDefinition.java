package com.lapissea.jorth.redo;

import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.type.JType;
import com.lapissea.jorth.lang.type.Visibility;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;

public final class FieldDefinition{
	
	public final ClassDefinition owner;
	public final String          name;
	public final JType           type;
	private      Visibility      visibility;
	
	private AccessSet access = AccessSet.DEFAULT;
	
	public FieldDefinition(ClassDefinition owner, String name, JType type){
		this.owner = owner;
		this.name = name;
		this.type = type;
	}
	
	public FieldDefinition abstractAcc(){
		access = access.andAbstr();
		return this;
	}
	
	public FieldDefinition staticAcc(){
		access = access.andStat();
		return this;
	}
	public FieldDefinition finalAcc(){
		access = access.andFin();
		return this;
	}
	public FieldDefinition finalAcc(CodeArg init) throws MalformedJorth{
		return finalAcc().init(init);
	}
	
	public FieldDefinition staticFinal(){
		access = access.andStat().andFin();
		return this;
	}
	public FieldDefinition staticFinal(CodeArg init) throws MalformedJorth{
		return staticFinal().init(init);
	}
	private FieldDefinition init(CodeArg init) throws MalformedJorth{
		var body = owner.staticInit().body();
		init.accept(body);
		body.set(this);
		return this;
	}
	
	public AccessSet access(){
		return access;
	}
	
	public FieldDefinition visibility(Visibility visibility){
		this.visibility = visibility;
		return this;
	}
	public Visibility visibility(){
		return visibility;
	}
	
	public FieldVisitor visit(ClassWriter writer){
		var descriptor = type.jvmDescriptorStr();
		var signature  = type.jvmSignatureStr();
		
		var access = visibility().flag|access().flags();
		return writer.visitField(access, name, descriptor, signature, null);
	}
}
