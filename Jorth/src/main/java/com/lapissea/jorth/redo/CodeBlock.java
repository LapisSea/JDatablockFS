package com.lapissea.jorth.redo;

import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import com.lapissea.jorth.lang.info.FunctionInfo;
import com.lapissea.jorth.lang.type.ClassInfo;
import com.lapissea.jorth.lang.type.GenericType;
import com.lapissea.jorth.lang.type.JType;
import com.lapissea.jorth.lang.type.TypeSource;
import com.lapissea.jorth.lang.type.TypeStack;
import com.lapissea.jorth.redo.Insn.*;
import org.objectweb.asm.MethodVisitor;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

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
	
	
	public CodeBlock getThis(FieldDefinition field) throws MalformedJorth{
		return get("this").get(field);
	}
	public CodeBlock get(FieldDefinition field) throws MalformedJorth{
		return add(GetFieldOp.simulate(localStack, typeSource, field));
	}
	
	public CodeBlock get(String localVal) throws MalformedJorth{
		doGetLocal(localVal);
		return this;
	}
	
	private void doGetLocal(String localVal) throws MalformedJorth{
		Local local = localValues.get(localVal);
		if(local == null){
			if(localVal.equals("this") && fnOwner.access().isStatic()){
				throw new MalformedJorth("Cannot get 'this' from a static function");
			}
			throw new MalformedJorth("Unknown localValue: " + localVal);
		}
		add(GetLocal.simulate(localStack, local.type.asGeneric(), localVal, local.index));
	}
	
	public CodeBlock val(int val) throws MalformedJorth{
		return add(IVal.simulate(localStack, val));
	}
	public CodeBlock val(long val) throws MalformedJorth{
		return add(LVal.simulate(localStack, val));
	}
	public CodeBlock val(float val) throws MalformedJorth{
		return add(FVal.simulate(localStack, val));
	}
	public CodeBlock val(double val) throws MalformedJorth{
		return add(DVal.simulate(localStack, val));
	}
	public CodeBlock val(boolean val) throws MalformedJorth{
		return add(BVal.simulate(localStack, val));
	}
	public CodeBlock val(String val) throws MalformedJorth{
		return add(StrVal.simulate(localStack, val));
	}
	public CodeBlock val(Class<?> val) throws MalformedJorth{
		return val(ClassName.of(val));
	}
	public CodeBlock val(ClassName val) throws MalformedJorth{
		return add(ClassVal.simulate(localStack, val));
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
		return newObj(ClassName.of(clazz), c -> { });
	}
	public CodeBlock newObj(ClassName clazz) throws MalformedJorth{
		return newObj(clazz, c -> { });
	}
	public CodeBlock newObj(Class<?> clazz, CodeArg arguments) throws MalformedJorth{
		return newObj(ClassName.of(clazz), arguments);
	}
	public CodeBlock newObj(ClassName clazz, CodeArg arguments) throws MalformedJorth{
		var type = GenericType.of(clazz);
		if(type.dims() != 0){
			add(NewOp.simulate(localStack, type, false));
			var args = doArgs(arguments);
			if(args.size() != 0){
				throw new MalformedJorth("Cannot pass arguments to an array type");
			}
			return this;
		}
		add(NewOp.simulate(localStack, type, true));
		var args = doArgs(arguments);
		var fn   = resolveFunction(typeSource.byName(clazz), "<init>", args);
		return add(InvokeOp.simulate(localStack, typeSource, cName(), fn, false));
	}
	
	public CodeBlock call(String name) throws MalformedJorth{
		return call(name, 0);
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
		
		var args = doArgs(gatherArguments);
		
		FunctionInfo fn = resolveFunction(caller, name, args);
		return add(InvokeOp.simulate(localStack, typeSource, cName(), fn, false));
	}
	
	private List<JType> doArgs(CodeArg gatherArguments) throws MalformedJorth{
		var mark = localStack.size();
		gatherArguments.accept(this);
		return readCallStack(mark);
	}
	
	public CodeBlock call(Class<?> staticCaller, String name, CodeArg gatherArguments) throws MalformedJorth{
		return call(ClassName.of(staticCaller), name, gatherArguments);
	}
	public CodeBlock call(ClassName staticCaller, String name, CodeArg gatherArguments) throws MalformedJorth{
		var cl = typeSource.byName(staticCaller);
		
		var args = doArgs(gatherArguments);
		
		var fn = cl.getFunction(new FunctionInfo.Signature(name, args));
		
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
		return add(InvokeOp.simulate(localStack, typeSource, cName(), fn, false));
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
	
	public CodeBlock setThis(FieldDefinition field) throws MalformedJorth{
		return get("this").swap().set(field);
	}
	public CodeBlock set(FieldDefinition field) throws MalformedJorth{
		return add(PutFieldOp.simulate(localStack, typeSource, field));
	}
	public CodeBlock pop() throws MalformedJorth{
		return add(PopOp.simulate(localStack));
	}
	public CodeBlock dup() throws MalformedJorth{
		return add(DupOp.simulate(localStack));
	}
	public CodeBlock swap() throws MalformedJorth{
		return add(SwapOp.simulate(localStack));
	}
	
	public void callSuper(CodeArg gatherArguments) throws MalformedJorth{
		get("this");
		var          args    = doArgs(gatherArguments);
		FunctionInfo superFn = resolveFunction(fnOwner.owner().superType(), fnOwner.name(), args);
		add(InvokeOp.simulate(localStack, typeSource, cName(), superFn, true));
	}
	/**
	 * Calls super of the current function. The function has to be static. It will automatically gather all arguments and pass them.
	 *
	 * @return
	 * @throws MalformedJorth
	 */
	public CodeBlock callSuperAutoPass() throws MalformedJorth{
		get("this");
		for(String argName : fnOwner.getArgNames()){
			get(argName);
		}
		FunctionInfo superFn = resolveFunction(fnOwner.owner().superType(), fnOwner.name(), fnOwner.getArgs());
		return add(InvokeOp.simulate(localStack, typeSource, cName(), superFn, true));
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
	
	public CodeBlock cast(GenericType type) throws MalformedJorth{
		return add(CastOp.simulate(localStack, typeSource, type));
	}
	public CodeBlock setArrayElement() throws MalformedJorth{
		return add(PutElementOp.simulate(localStack, typeSource));
	}
	
	public static class BootstrapFnBuilder{
		private ClassName         owner;
		private String            functionName;
		private List<GenericType> extraArgs=new ArrayList<>();
		
		public BootstrapFnBuilder caller(Class<?> owner, String functionName){
			return caller(ClassName.of(owner), functionName);
		}
		public BootstrapFnBuilder caller(ClassName owner, String functionName){
			this.owner = owner;
			this.functionName = functionName;
		}
		
		public BootstrapFnBuilder arg(Class<?> arg){
			return arg(GenericType.of(arg));
		}
		public BootstrapFnBuilder arg(GenericType arg){
			extraArgs.add(arg);
			return this;
		}
	}
	
	public CodeBlock callVirtual(Consumer<BootstrapFnBuilder> bootstrap ) throws MalformedJorth{
	return add()
	}
	
}
