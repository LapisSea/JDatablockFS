package com.lapissea.jorth;

import com.lapissea.jorth.exceptions.IllegalClassState;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import com.lapissea.jorth.lang.FunctionInfo;
import com.lapissea.jorth.lang.type.ClassInfo;
import com.lapissea.jorth.lang.type.ClassType;
import com.lapissea.jorth.lang.type.GenericType;
import com.lapissea.jorth.lang.type.JType;
import com.lapissea.jorth.lang.type.Visibility;
import org.objectweb.asm.ClassWriter;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

import static org.objectweb.asm.Opcodes.ACC_VARARGS;

public final class FunctionDefinition extends AnnotationContainer<FunctionDefinition> implements FunctionInfo{
	
	private final ClassDefinition owner;
	private       AccessSet       access     = AccessSet.ABSTRACT;
	private       Visibility      visibility = Visibility.PUBLIC;
	
	private final String                       name;
	private final LinkedHashMap<String, JType> args             = new LinkedHashMap<>();
	private       JType                        returnType;
	private       List<ClassName>              thrownExceptions = Collections.emptyList();
	private       boolean                      varargs;
	
	private CodeBlock body;
	
	
	public FunctionDefinition(ClassDefinition owner, String name){
		this.owner = Objects.requireNonNull(owner);
		this.name = Objects.requireNonNull(name);
		if(isEnumConstructor()) visibility = Visibility.PRIVATE;
	}
	
	@Override
	public boolean isVarargs(){
		return varargs;
	}
	public FunctionDefinition varargs(){
		preBodyCheck();
		this.varargs = true;
		return this;
	}
	
	public FunctionDefinition arg(Type type, String name){
		return arg(GenericType.of(type), name);
	}
	public FunctionDefinition arg(ClassName type, String name){
		return arg(GenericType.of(type), name);
	}
	public FunctionDefinition arg(JType type, String name){
		preBodyCheck();
		args.put(name, type);
		return this;
	}
	
	public FunctionDefinition returns(Class<?> type, Class<?>... genericArgs){
		return returns(GenericType.of(type).withArgs(genericArgs));
	}
	public FunctionDefinition returns(Type type){
		return returns(GenericType.of(type));
	}
	public FunctionDefinition returns(JType type){
		preBodyCheck();
		this.returnType = type;
		return this;
	}
	public FunctionDefinition throwsException(Class<?>... exceptions){
		return throwsException(Arrays.stream(exceptions).map(ClassName::of).toList());
	}
	public FunctionDefinition throwsException(List<ClassName> exceptions){
		preBodyCheck();
		this.thrownExceptions = Collections.unmodifiableList(exceptions);
		return this;
	}
	public FunctionDefinition visibility(Visibility visibility){
		this.visibility = Objects.requireNonNull(visibility);
		return this;
	}
	public Visibility visibility(){
		return visibility;
	}
	public FunctionDefinition staticAcc(){
		return access(access.andStat());
	}
	public FunctionDefinition finalAcc(){
		return access(access.andFin());
	}
	public FunctionDefinition abstractAcc(){
		return access(access.andAbstr());
	}
	public FunctionDefinition access(AccessSet access){
		if(this.access.isStatic() != access.isStatic()){
			preBodyCheck();
		}
		if(access.isAbstract() && body != null){
			throw new IllegalStateException("Can not make function abstract when it has a body!");
		}
		this.access = Objects.requireNonNull(access);
		return this;
	}
	public AccessSet access(){
		return access;
	}
	@Override
	public String name(){
		return name;
	}
	@Override
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
		access(access.withoutAbstr());
		if(isEnumConstructor()) owner.validateEnumConstructor(this);
		var existing = owner.finalize(this);
		
		if(existing != this && existing != null){
			checkVal(returnType, existing.returnType);
			checkVal(access.isStatic(), existing.access.isStatic());
			return existing.body();
		}
		
		
		var body = new CodeBlock(null, owner.typeSource, this);
		if(!access.isStatic()){
			body.defineLocalValue("this", new GenericType(Objects.requireNonNull(owner.name(), "Class name must be defined before using a function")), false);
		}
		String hiddenName = "€enum€name", hiddenOrdinal = "€enum€ordinal";
		if(isEnumConstructor()){
			while(args.containsKey(hiddenName)) hiddenName += "€";
			while(args.containsKey(hiddenOrdinal)) hiddenOrdinal += "€";
			body.defineLocalValue(hiddenName, GenericType.of(String.class), false);
			body.defineLocalValue(hiddenOrdinal, GenericType.INT, false);
		}
		for(var e : args.entrySet()){
			body.defineLocalValue(e.getKey(), e.getValue().asGeneric(), false);
		}
		if(isEnumConstructor()) body.enumSuper(hiddenName, hiddenOrdinal);
		return body;
	}
	boolean isEnumConstructor(){ return owner.getType() == ClassType.ENUM && name.equals("<init>"); }
	public FunctionInfo.Signature makeSignature(){
		return new FunctionInfo.Signature(name, getArgs());
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
		
		var accessFlags = visibility.flag|access.flags()|(varargs? ACC_VARARGS : 0);
		
		var argTypes = getArgs();
		
		var descriptor = makeFunSig(returnType, argTypes, false);
		var signature  = makeFunSig(returnType, argTypes, true);
		
		if(descriptor.equals(signature)) signature = null;
		
		String[] exceptions = thrownExceptions.isEmpty()? null :
		                      thrownExceptions.stream().map(ClassName::slashed).toArray(String[]::new);
		var fn = writer.visitMethod(accessFlags, name, descriptor, signature, exceptions);
		for(AnnotationDefinition annotation : annotations){
			annotation.visit(fn);
		}
		if(body != null){
			body.visit(fn);
			if(!body.terminates()){
				try{
					body.mergeBranch();
				}catch(MalformedJorth e){
					throw new MalformedJorth("Failed to merge branch before returning on " + this, e);
				}
				body.implicitReturn(fn);
			}
			fn.visitMaxs(0, 0);
		}
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
	public List<JType> getArgs(){
		if(!isEnumConstructor()) return List.copyOf(args.values());
		var types = new ArrayList<JType>(args.size() + 2);
		types.add(GenericType.of(String.class));
		types.add(GenericType.INT);
		types.addAll(args.values());
		return List.copyOf(types);
	}
	public List<String> getArgNames(){
		return List.copyOf(args.keySet());
	}
	
	@Override
	public boolean isStatic(){
		return access.isStatic();
	}
	@Override
	public boolean isFinal(){
		return access().isFinal();
	}
	@Override
	public ClassInfo ownerInfo(){
		return owner.getClassInfo();
	}
	@Override
	public List<JType> argumentTypes(){
		return getArgs();
	}
	@Override
	public Object defaultEnumValue(){
		throw new UnsupportedOperationException();
	}
	
	@Override
	public String toString(){
		return owner.name() + "#" + makeSignature();
	}
	public FunctionDefinition override() throws MalformedJorth{
		var info = owner.getFunctionOverride(makeSignature());
		return returns(info.returnType()).throwsException(info.getThrownExceptions()).annotation(Override.class);
	}
	@Override
	public List<ClassName> getThrownExceptions(){
		return thrownExceptions;
	}
}
