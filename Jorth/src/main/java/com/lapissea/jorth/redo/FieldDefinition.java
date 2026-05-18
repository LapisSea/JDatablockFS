package com.lapissea.jorth.redo;

import com.lapissea.jorth.lang.type.JType;

import java.util.function.Consumer;

public final class FieldDefinition{
	
	public final ClassDefinition owner;
	public final String          name;
	public final JType           type;
	
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
	public FieldDefinition finalAcc(Consumer<CodeBlock> init){
		return finalAcc().init(init);
	}
	
	public FieldDefinition staticFinal(){
		access = access.andStat().andFin();
		return this;
	}
	public FieldDefinition staticFinal(Consumer<CodeBlock> init){
		return staticFinal().init(init);
	}
	private FieldDefinition init(Consumer<CodeBlock> init){
		var body = owner.staticInit().body();
		init.accept(body);
		body.set(this);
		return this;
	}
	
	public AccessSet getAccess(){
		return access;
	}
}
