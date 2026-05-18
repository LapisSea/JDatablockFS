package com.lapissea.jorth.redo;

import com.lapissea.jorth.lang.ClassName;
import com.lapissea.util.NotImplementedException;

import java.util.function.Consumer;

public class CodeBlock{
	
	
	public CodeBlock get(FieldDefinition field){
		throw new NotImplementedException();
	}
	
	public CodeBlock get(String localVal){
		throw new NotImplementedException();
	}
	
	public CodeBlock val(int val){
		throw new NotImplementedException();
	}
	public CodeBlock val(String val){
		throw new NotImplementedException();
	}
	
	public CodeBlock equalityOp(){
		throw new NotImplementedException();
	}
	
	public CodeBlock returnOp(){
		throw new NotImplementedException();
	}
	public CodeBlock ifTrue(Consumer<CodeBlock> code){
		throw new NotImplementedException();
	}
	public CodeBlock ifEquality(Consumer<CodeBlock> code){
		return equalityOp().ifTrue(code);
	}
	public CodeBlock elseRun(Consumer<CodeBlock> code){
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
	public CodeBlock call(String name, Consumer<CodeBlock> gatherArguments){
		throw new NotImplementedException();
	}
	
	public CodeBlock call(FunctionDefinition fn, int argumentCount){
		throw new NotImplementedException();
	}
	public CodeBlock call(FunctionDefinition fn, Consumer<CodeBlock> gatherArguments){
		throw new NotImplementedException();
	}
	
	public void set(FieldDefinition field){
		throw new NotImplementedException();
	}
	public CodeBlock pop(){
		throw new NotImplementedException();
	}
}
