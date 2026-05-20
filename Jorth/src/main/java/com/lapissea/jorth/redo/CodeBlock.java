package com.lapissea.jorth.redo;

import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import com.lapissea.jorth.lang.info.FunctionInfo;
import com.lapissea.jorth.lang.type.ClassInfo;
import com.lapissea.jorth.lang.type.GenericType;
import com.lapissea.jorth.lang.type.JType;
import com.lapissea.jorth.lang.type.TypeSource;
import com.lapissea.jorth.lang.type.TypeStack;
import com.lapissea.jorth.redo.Insn.ConditionalJump;
import com.lapissea.jorth.redo.Insn.DupOp;
import com.lapissea.jorth.redo.Insn.Equality;
import com.lapissea.jorth.redo.Insn.GetFieldOp;
import com.lapissea.jorth.redo.Insn.GetLocal;
import com.lapissea.jorth.redo.Insn.IVal;
import com.lapissea.jorth.redo.Insn.InvokeOp;
import com.lapissea.jorth.redo.Insn.NewOp;
import com.lapissea.jorth.redo.Insn.PopOp;
import com.lapissea.jorth.redo.Insn.PutFieldOp;
import com.lapissea.jorth.redo.Insn.ReturnOp;
import com.lapissea.jorth.redo.Insn.StrVal;
import org.objectweb.asm.MethodVisitor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class CodeBlock{
	
	private record Local(JType type, int index, boolean canRemove){ }
	
	private final Map<String, Local> localValues = new HashMap<>();
	
	private final List<Insn> insns = new ArrayList<>();
	private final TypeStack  localStack;
	
	private final TypeSource         typeSource;
	private final FunctionDefinition fnOwner;
	
	public CodeBlock(TypeStack baseStack, TypeSource typeSource, FunctionDefinition fnOwner){
		localStack = new TypeStack(baseStack);
		this.typeSource = typeSource;
		this.fnOwner = fnOwner;
	}
	
	private CodeBlock add(Insn insn) throws MalformedJorth{
		if(terminates()){
			throw new MalformedJorth("This code block has terminated");
		}
		insns.add(Objects.requireNonNull(insn));
		return this;
	}
	
	public void defineLocalValue(String name, JType type, boolean canRemove) throws MalformedJorth{
		Objects.requireNonNull(name);
		Objects.requireNonNull(type);
		if(localValues.containsKey(name)){
			throw new MalformedJorth("Duplicated localValue: " + name);
		}
		int index = allocateNewSlot();
		localValues.put(name, new Local(type, index, canRemove));
	}
	
	private CodeBlock createBlockFromHere(CodeArg code) throws MalformedJorth{
		var block = new CodeBlock(localStack, typeSource, fnOwner);
		for(var e : localValues.entrySet()){
			var v = e.getValue();
			block.localValues.put(e.getKey(), v.canRemove? new Local(v.type, v.index, false) : v);
		}
		code.accept(block);
		return block;
	}
	
	private int allocateNewSlot(){
		return localValues.values().stream().mapToInt(i -> i.index() + i.type.getBaseType().slots).max().orElse(0);
	}
	
	
	public CodeBlock get(FieldDefinition field) throws MalformedJorth{
		return add(GetFieldOp.simulate(localStack, typeSource, field.getInfo()));
	}
	
	public CodeBlock get(String localVal) throws MalformedJorth{
		doGetLocal(localVal);
		return this;
	}
	
	private void doGetLocal(String localVal) throws MalformedJorth{
		Local local = localValues.get(localVal);
		if(local == null){
			throw new MalformedJorth("Unknown localValue: " + localVal);
		}
		add(GetLocal.simulate(localStack, local.type.asGeneric(), localVal, local.index));
	}
	
	public CodeBlock val(int val) throws MalformedJorth{
		return add(IVal.simulate(localStack, val));
	}
	public CodeBlock val(String val) throws MalformedJorth{
		return add(StrVal.simulate(localStack, val));
	}
	
	public CodeBlock equalityOp() throws MalformedJorth{
		return add(Equality.simulate(typeSource, localStack, true));
	}
	
	public CodeBlock returnOp() throws MalformedJorth{
		return add(ReturnOp.simulate(fnOwner.returnType(), typeSource, localStack, true));
	}
	public CodeBlock ifTrue(CodeArg code) throws MalformedJorth{
		return trueBlock(code, ConditionalJump.Type.TRUE_BOOL);
	}
	public CodeBlock ifEquality(CodeArg code) throws MalformedJorth{
		return equalityOp().ifTrue(code);
//		return trueBlock(code, ConditionalJump.Type.EQUALITY);
	}
	private CodeBlock trueBlock(CodeArg code, ConditionalJump.Type type) throws MalformedJorth{
		var block = createBlockFromHere(code);
		return add(ConditionalJump.simulate(localStack, typeSource, type, block, null));
	}
	public CodeBlock elseRun(CodeArg code) throws MalformedJorth{
		var lastInsn = insns.isEmpty()? null : insns.getLast();
		if(!(lastInsn instanceof ConditionalJump jump)){
			throw new MalformedJorth("Must be run after a conditional operation");
		}
		if(jump.onFalse() != null){
			throw new MalformedJorth("Duplicate else call");
		}
		insns.removeLast();
		var block = createBlockFromHere(code);
		return add(jump.withFalse(localStack, block));
	}
	public CodeBlock newObj(Class<?> clazz) throws MalformedJorth{
		return newObj(ClassName.of(clazz));
	}
	public CodeBlock newObj(ClassName clazz) throws MalformedJorth{
		add(NewOp.simulate(localStack, new GenericType(clazz), true));
		var fn = resolveFunction(typeSource.byName(clazz), "<init>", List.of());
		return add(InvokeOp.simulate(localStack, typeSource, cName(), fn, false));
	}
	public CodeBlock call(String name, int argumentCount) throws MalformedJorth{
		var stackSize = localStack.size();
		var mark      = stackSize - argumentCount;
		var caller    = typeSource.byType(localStack.peek(mark - 1));
		
		List<JType> args = argumentCount == 0? List.of() : readCallStack(mark);
		
		FunctionInfo fn = resolveFunction(caller, name, args);
		return add(InvokeOp.simulate(localStack, typeSource, cName(), fn, false));
	}
	public CodeBlock call(String name, CodeArg gatherArguments) throws MalformedJorth{
		var caller = typeSource.byType(localStack.peekLast());
		
		var mark = localStack.size();
		gatherArguments.accept(this);
		var args = readCallStack(mark);
		
		FunctionInfo fn = resolveFunction(caller, name, args);
		return add(InvokeOp.simulate(localStack, typeSource, cName(), fn, false));
	}
	private List<JType> readCallStack(int mark) throws MalformedJorth{
		var argCount = localStack.size() - mark;
		if(argCount<0) throw new MalformedJorth("Negative stack delta inside arg block");
		var args = new ArrayList<JType>(argCount);
		for(int i = 0; i<argCount; i++){
			args.add(localStack.peek(mark + i));
		}
		return args;
	}
	
	public CodeBlock call(FunctionDefinition fn) throws MalformedJorth{
		return add(InvokeOp.simulate(localStack, typeSource, cName(), fn.getInfo(), false));
	}
	public CodeBlock call(FunctionDefinition fn, CodeArg gatherArguments) throws MalformedJorth{
		var mark = localStack.size();
		gatherArguments.accept(this);
		var argCount = localStack.size() - mark;
		
		var fnArgs = fn.getArgs();
		if(argCount != fnArgs.size()){
			throw new MalformedJorth("Function argument takes " + fnArgs.size() + " but got " + argCount);
		}
		return call(fn);
	}
	
	public CodeBlock set(FieldDefinition field) throws MalformedJorth{
		return add(PutFieldOp.simulate(localStack, typeSource, field.getInfo()));
	}
	public CodeBlock pop() throws MalformedJorth{
		return add(PopOp.simulate(localStack));
	}
	public CodeBlock dup() throws MalformedJorth{
		return add(DupOp.simulate(localStack));
	}
	
	public void callSuper() throws MalformedJorth{
		get("this");
		ClassInfo    parent  = fnOwner.owner().superType();
		FunctionInfo superFn = resolveFunction(parent, fnOwner.name(), List.of());
		add(InvokeOp.simulate(localStack, typeSource, cName(), superFn, true));
	}
	
	private ClassName cName(){
		return fnOwner.owner().name();
	}
	
	public void visit(MethodVisitor fn){
		for(Insn i : insns){
			i.visit(fn);
		}
	}
	void implicitReturn(MethodVisitor fn){
		if(terminates()) return;
		try{
			ReturnOp.simulate(fnOwner.returnType(), typeSource, localStack, false).visit(fn);
		}catch(MalformedJorth e){
			throw new RuntimeException("Failed to return on " + fnOwner, e);
		}
	}
	
	private FunctionInfo resolveFunction(ClassInfo cInfo, String functionName, List<JType> args) throws MalformedJorth{
		try{
			return cInfo.getFunction(new FunctionInfo.Signature(functionName, args));
		}catch(MalformedJorth e){
			return cInfo.getFunctionsByName(functionName).filter(f -> {
				var argsF = f.argumentTypes();
				if(argsF.size() != args.size()) return false;
				for(int i = 0; i<argsF.size(); i++){
					var a = argsF.get(i).asGeneric();
					var b = args.get(i).asGeneric();
					try{
						if(!b.instanceOf(typeSource, a)){
							return false;
						}
					}catch(MalformedJorth ex){
						throw new RuntimeException(ex);
					}
				}
				return true;
			}).findAny().orElseThrow(() -> e);
		}
	}
	
	public boolean terminates(){
		if(insns.isEmpty()) return false;
		Insn last = insns.getLast();
		return last instanceof ReturnOp;
	}
	
	@Override
	protected CodeBlock clone() throws CloneNotSupportedException{
		CodeBlock cloned = new CodeBlock(this.localStack.clone(), this.typeSource, this.fnOwner);
		cloned.localValues.putAll(this.localValues);
		cloned.insns.addAll(this.insns);
		return cloned;
	}
	public boolean stacksMatch(CodeBlock other){
		return stacksMatch(other.localStack);
	}
	public boolean stacksMatch(TypeStack localStack){
		return this.localStack.equals(localStack);
	}
}
