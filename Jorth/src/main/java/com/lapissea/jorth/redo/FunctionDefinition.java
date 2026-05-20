package com.lapissea.jorth.redo;

import com.lapissea.jorth.exceptions.IllegalClassState;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.info.FunctionInfo;
import com.lapissea.jorth.lang.type.GenericType;
import com.lapissea.jorth.lang.type.JType;
import com.lapissea.jorth.lang.type.Visibility;
import org.objectweb.asm.ClassWriter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

public class FunctionDefinition{
	
	private final ClassDefinition owner;
	private       AccessSet       access     = AccessSet.DEFAULT;
	private       Visibility      visibility = Visibility.PUBLIC;
	
	private final String                       name;
	private final LinkedHashMap<String, JType> args = new LinkedHashMap<>();
	private       JType                        returnType;
	
	private CodeBlock body;
	
	
	public FunctionDefinition(ClassDefinition owner, String name){
		this.owner = Objects.requireNonNull(owner);
		this.name = Objects.requireNonNull(name);
	}
	
	public FunctionDefinition arg(Class<?> type, String name){
		return arg(GenericType.of(type), name);
	}
	public FunctionDefinition arg(JType type, String name){
		preBodyCheck();
		var oldSignature = makeSignature();
		args.put(name, type);
		owner.updateSignature(oldSignature, makeSignature(), this);
		return this;
	}
	
	public FunctionDefinition returns(JType type){
		preBodyCheck();
		this.returnType = type;
		return this;
	}
	
	public FunctionDefinition visibility(Visibility visibility){
		this.visibility = Objects.requireNonNull(visibility);
		return this;
	}
	public Visibility visibility(){
		return visibility;
	}
	public FunctionDefinition access(AccessSet access){
		if(this.access.isStatic() != access.isStatic()){
			preBodyCheck();
		}
		this.access = Objects.requireNonNull(access);
		return this;
	}
	public AccessSet access(){
		return access;
	}
	public String name(){
		return name;
	}
	public JType returnType(){
		return returnType;
	}
	public ClassDefinition owner(){
		return owner;
	}
	public CodeBlock body() throws MalformedJorth{
		var b = body;
		if(b == null) b = body = initBody();
		return b;
	}
	
	private CodeBlock initBody() throws MalformedJorth{
		
		if(args.isEmpty()){
			owner.updateSignature(null, makeSignature(), this);
		}
		
		var existing = owner.getFunction(makeSignature());
		if(existing != this && existing != null){
			checkVal(returnType, existing.returnType);
			checkVal(access.isStatic(), existing.access.isStatic());
			return existing.body();
		}
		
		
		var body = new CodeBlock(owner.typeSource, this);
		if(!access.isStatic()){
			body.defineLocalValue("this", new GenericType(owner.name()));
		}
		for(var e : args.entrySet()){
			body.defineLocalValue(e.getKey(), e.getValue());
		}
		return body;
	}
	private FunctionInfo.Signature makeSignature(){
		return new FunctionInfo.Signature(name, List.copyOf(args.values()));
	}
	
	private void preBodyCheck(){
		if(body != null){
			throw new IllegalStateException("Can't change this function property after body has been used");
		}
	}
	private static void checkVal(Object old, Object newV){
		if(!Objects.equals(old, newV)){
			throw new IllegalClassState("Disagreement on function definition:" +
			                            "\n  Has:     " + old +
			                            "\n  But got: " + newV);
		}
	}
	
	public void visit(ClassWriter writer) throws MalformedJorth{
		
		var accessFlags = visibility.flag|access.flags();
		
		var argTypes = new ArrayList<JType>(args.values());
		
		var descriptor = makeFunSig(returnType, argTypes, false);
		var signature  = makeFunSig(returnType, argTypes, true);
		
		if(descriptor.equals(signature)) signature = null;
		
		var fn = writer.visitMethod(accessFlags, name, descriptor, signature, null);
		body.visit(fn);
		fn.visitMaxs(0, 0);
		fn.visitEnd();
	}
	private static String makeFunSig(JType returnType, Collection<JType> args, boolean signature){
		
		int len = 2 + (returnType != null? returnType.jvmStringLen(signature) : 1);
		for(var arg : args){
			len += arg.jvmStringLen(signature);
		}
		
		StringBuilder result = new StringBuilder(len);
		result.append('(');
		for(var arg : args){
			arg.jvmString(result, signature);
		}
		result.append(')');
		if(returnType != null) returnType.jvmString(result, signature);
		else result.append('V');
		
		assert result.length() == len : result.length() + " " + len;
		
		return result.toString();
	}
}
