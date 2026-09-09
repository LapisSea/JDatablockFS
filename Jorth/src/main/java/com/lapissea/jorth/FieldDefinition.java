package com.lapissea.jorth;

import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import com.lapissea.jorth.lang.type.FieldInfo;
import com.lapissea.jorth.lang.type.JType;
import com.lapissea.jorth.lang.type.Visibility;
import org.objectweb.asm.ClassWriter;

import static org.objectweb.asm.Opcodes.ACC_ENUM;

public final class FieldDefinition extends AnnotationContainer<FieldDefinition> implements FieldInfo{
	
	public final ClassDefinition owner;
	public final String          name;
	public final JType           type;
	private      Visibility      visibility = Visibility.PUBLIC;
	CodeArg enumConstantInit;
	
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
	public FieldDefinition asEnumConstant(CodeArg init){
		enumConstantInit = init;
		return staticFinal();
	}
	public boolean isEnumConstant(){
		return enumConstantInit != null;
	}
	
	private FieldDefinition init(CodeArg init) throws MalformedJorth{
		var body = owner.staticInit().body();
		init.accept(body);
		body.setField(this);
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
	
	public void visit(ClassWriter writer){
		var descriptor = type.jvmDescriptorStr();
		var signature  = type.jvmSignatureStr();
		var access     = visibility().flag|access().flags()|(isEnumConstant()? ACC_ENUM : 0);
		
		var fw = writer.visitField(access, name, descriptor, signature, null);
		for(AnnotationDefinition annotation : annotations){
			annotation.visit(fw);
		}
		
		fw.visitEnd();
	}
	
	@Override
	public boolean isStatic(){
		return access.isStatic();
	}
	@Override
	public ClassName owner(){
		return owner.name();
	}
	@Override
	public JType type(){
		return type;
	}
	@Override
	public String name(){
		return name;
	}
}
