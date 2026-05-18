package com.lapissea.jorth.redo;

import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import com.lapissea.jorth.lang.type.JType;
import com.lapissea.jorth.lang.type.TypeSource;
import com.lapissea.jorth.lang.type.TypeStack;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.function.UnsafeConsumer;
import org.objectweb.asm.MethodVisitor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class CodeBlock{
	
	private record Local(JType type, int index){ }
	
	private final Map<String, Local> localValues = new HashMap<>();
	
	private final List<Insn> insn       = new ArrayList<>();
	private final TypeStack  localStack = new TypeStack(null);
	private final TypeSource typeSource;
	private final JType      returnType;
	
	public CodeBlock(TypeSource typeSource, JType returnType){
		this.typeSource = typeSource;
		this.returnType = returnType;
	}
	
	public void defineLocalValue(ArgInfo info) throws MalformedJorth{
		defineLocalValue(info.name(), info.type());
	}
	public void defineLocalValue(String name, JType type) throws MalformedJorth{
		Objects.requireNonNull(name);
		Objects.requireNonNull(type);
		if(localValues.containsKey(name)){
			throw new MalformedJorth("Duplicated localValue: " + name);
		}
		int index = allocateNewSlot();
		localValues.put(name, new Local(type, index));
	}
	private int allocateNewSlot(){
		return localValues.values().stream().mapToInt(i -> i.index() + i.type.getBaseType().slots).max().orElse(0);
	}
	
	
	public CodeBlock get(FieldDefinition field){
		throw new NotImplementedException();
	}
	
	public CodeBlock get(String localVal) throws MalformedJorth{
		doGetLocal(localVal);
		return this;
	}
	
	private void doGetLocal(String localVal) throws MalformedJorth{
		Local local = localValues.get(localVal);
		if(local == null){
			throw new IllegalArgumentException("Unknown localValue: " + localVal);
		}
		exec(new Insn.GetLocal(local.type.asGeneric(), localVal, local.index));
	}
	private void exec(Insn i) throws MalformedJorth{
		i.simulate(typeSource, localStack);
		insn.add(i);
	}
	
	public CodeBlock val(int val){
		throw new NotImplementedException();
	}
	public CodeBlock val(String val){
		throw new NotImplementedException();
	}
	
	public CodeBlock equalityOp() throws MalformedJorth{
		exec(new Insn.Equality());
		return this;
	}
	
	public CodeBlock returnOp(){
		throw new NotImplementedException();
	}
	public CodeBlock ifTrue(UnsafeConsumer<CodeBlock, MalformedJorth> code){
		throw new NotImplementedException();
	}
	public CodeBlock ifEquality(UnsafeConsumer<CodeBlock, MalformedJorth> code) throws MalformedJorth{
		return equalityOp().ifTrue(code);
	}
	public CodeBlock elseRun(UnsafeConsumer<CodeBlock, MalformedJorth> code){
		throw new NotImplementedException();
	}
	public CodeBlock newObj(Class<?> clazz){
		return newObj(ClassName.of(clazz));
	}
	public CodeBlock newObj(ClassName clazz){
		throw new NotImplementedException();
	}
	public CodeBlock call(String name, int argumentCount){
		throw new NotImplementedException();
	}
	public CodeBlock call(String name, UnsafeConsumer<CodeBlock, MalformedJorth> gatherArguments){
		throw new NotImplementedException();
	}
	
	public CodeBlock call(FunctionDefinition fn, int argumentCount){
		throw new NotImplementedException();
	}
	public CodeBlock call(FunctionDefinition fn, UnsafeConsumer<CodeBlock, MalformedJorth> gatherArguments){
		throw new NotImplementedException();
	}
	
	public void set(FieldDefinition field){
		throw new NotImplementedException();
	}
	public CodeBlock pop() throws MalformedJorth{
		exec(Insn.PopOp.make(localStack));
		return this;
	}
	
	
	public void visit(MethodVisitor fn) throws MalformedJorth{
		for(Insn i : insn){
			i.visit(fn);
		}
		//implicit return
		if(!insn.isEmpty() && !(insn.getLast() instanceof Insn.ReturnOp)){
			Insn.ReturnOp.make(returnType, typeSource, localStack).visit(fn);
		}
	}
}
