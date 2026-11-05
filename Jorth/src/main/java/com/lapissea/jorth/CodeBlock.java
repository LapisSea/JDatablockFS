package com.lapissea.jorth;

import com.lapissea.jorth.Insn.*;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.exceptions.MissingLocalField;
import com.lapissea.jorth.lang.ClassName;
import com.lapissea.jorth.lang.FunctionInfo;
import com.lapissea.jorth.lang.LocalsArray;
import com.lapissea.jorth.lang.LocalsArray.Local;
import com.lapissea.jorth.lang.type.ClassInfo;
import com.lapissea.jorth.lang.type.ClassType;
import com.lapissea.jorth.lang.type.FieldInfo;
import com.lapissea.jorth.lang.type.GenericType;
import com.lapissea.jorth.lang.type.JType;
import com.lapissea.jorth.lang.type.TypeSource;
import com.lapissea.jorth.lang.type.TypeStack;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class CodeBlock{
	
	enum FreezeReason{
		EDITING_CHILD,
		FINISHED_EDITING;
	}
	
	private final LocalsArray localValues = new LocalsArray();
	
	private static final class LocalDebugRange{
		final int start;
		int end = -1;
		LocalDebugRange(int start){
			this.start = start;
		}
	}
	
	// Debug history info for debugging emit
	private final Map<Local, LocalDebugRange> debugLocals = new IdentityHashMap<>();
	
	private final List<Insn> insns = new ArrayList<>();
	private final TypeStack  localStack;
	
	private final TypeSource         typeSource;
	private final FunctionDefinition fnOwner;
	
	private FreezeReason freezeReason;
	private LoopContext  loopContext;
	
	public CodeBlock(TypeStack baseStack, TypeSource typeSource, FunctionDefinition fnOwner){
		localStack = new TypeStack(baseStack);
		this.typeSource = typeSource;
		this.fnOwner = fnOwner;
	}
	
	
	public TypeSource getTypeSource(){
		return typeSource;
	}
	
	public ClassName getTypeDef(String name){
		return fnOwner.owner().getTypeDef(name);
	}
	
	private CodeBlock add(Insn insn) throws MalformedJorth{
		checkFreeze();
		if(terminates()){
			throw new MalformedJorth("This code block has terminated");
		}
		// null means NOOP
		if(insn != null) insns.add(insn);
		return this;
	}
	
	private void preInsn() throws MalformedJorth{
		preInsn(true);
	}
	private void preInsn(boolean checkBranch) throws MalformedJorth{
		if(terminates()){
			throw new MalformedJorth("This code block has terminated");
		}
		checkFreeze();
		if(checkBranch) mergeBranch();
	}
	
	void mergeBranch() throws MalformedJorth{
		if(!insns.isEmpty() && insns.getLast() instanceof BranchingInsn branching){
			branching.merge(localStack);
		}
	}
	
	private void checkFreeze(){
		if(freezeReason != null){
			deny();
		}
	}
	private void deny(){
		throw new IllegalAccessError(switch(freezeReason){
			case EDITING_CHILD -> "Should not modify parent while in a lambda";
			case FINISHED_EDITING -> "The block is no longer editable";
		});
	}
	
	public CodeBlock scope(CodeArg code) throws MalformedJorth{
		preInsn();
		var block = createBlockFromHere(code);
		for(GenericType typ : block.localStack.getLocalPortion()){
			localStack.push(typ);
		}
		return add(InlineBlock.simulate(block));
	}
	
	public CodeBlock tryRun(CodeArg code) throws MalformedJorth{
		preInsn();
		if(!localStack.isEmpty()) throw new MalformedJorth("tryRun requires an empty operand stack; store incoming values in locals");
		var block = createBlockFromHere(Objects.requireNonNull(code));
		block.lockEditing();
		return add(new Insn.TryBlock(block));
	}
	/**
	 * Adds a scoped handler that discards the caught exception.
	 */
	public CodeBlock tryCatch(Class<? extends Throwable> type, CodeArg code) throws MalformedJorth{
		return tryCatch(type, null, code);
	}
	/**
	 * Adds a scoped handler. The exception is available the exceptionName.
	 * Example: {@code tryCatch(IOException.class, "error", c -> c.throwNew(RuntimeException.class, a -> a.get("error")))}.
	 * Handlers are matched in declaration order, and a catch hidden by an earlier catch is rejected.
	 */
	public CodeBlock tryCatch(Class<? extends Throwable> type, String exceptionName, CodeArg code) throws MalformedJorth{
		return tryCatch(ClassName.of(type), exceptionName, code);
	}
	/**
	 * Adds a scoped handler. The exception is available the exceptionName.
	 * Example: {@code tryCatch(IOException.class, "error", c -> c.throwNew(RuntimeException.class, a -> a.get("error")))}.
	 * Handlers are matched in declaration order, and a catch hidden by an earlier catch is rejected.
	 */
	public CodeBlock tryCatch(ClassName type, String exceptionName, CodeArg code) throws MalformedJorth{
		
		checkFreeze();
		if(insns.isEmpty() || !(insns.getLast() instanceof Insn.TryBlock tryBlock) || tryBlock.sealed){
			throw new MalformedJorth("Catch must follow an unfinished try block");
		}
		
		Objects.requireNonNull(type, "type");
		
		if(!type.instanceOf(typeSource, ClassName.of(Throwable.class))){
			throw new MalformedJorth("Catch type must extend Throwable but is " + type);
		}
		
		for(var handler : tryBlock.handlers){
			if(type.instanceOf(typeSource, handler.type())){
				throw new MalformedJorth("Unreachable catch: " + type);
			}
		}
		
		var block = createBlockFromHere(b -> {
			if(exceptionName != null){
				b.defineLocalValue(exceptionName, GenericType.of(type), false);
			}
			Objects.requireNonNull(code).accept(b);
		});
		block.lockEditing();
		
		tryBlock.handlers.add(new Insn.TryBlock.Handler(type, exceptionName == null? null : block.getLocal(exceptionName), block));
		
		return this;
	}
	/**
	 * Builds a (while) loop. Both callbacks have local scopes.<br/>
	 * The check must add one boolean to the stack, and normal body completion must preserve the entry stack.
	 */
	public CodeBlock loop(CodeArg check, CodeArg body) throws MalformedJorth{
		preInsn();
		Objects.requireNonNull(check, "check");
		Objects.requireNonNull(body, "body");
		var context    = new LoopContext(stackView());
		var checkBlock = createBlockFromHere(check, context);
		checkBlock.lockEditing();
		var bodyBlock = createBlockFromHere(body, context);
		bodyBlock.lockEditing();
		return add(WhileLoop.simulate(context, checkBlock, bodyBlock));
	}
	
	/**
	 * Inserts at the current place a code block that will be evaluated at class generation time.
	 * The block must preserve the stack and must not terminate.
	 */
	public CodeBlock lazyBlock(CodeArg code) throws MalformedJorth{
		preInsn();
		var stack = new TypeStack(null);
		localStack.totalStack().forEach(stack::push);
		var localValues = this.localValues.clone();
		
		return add(LazyBlock.simulate(() -> {
			
			var block = new CodeBlock(new TypeStack(stack), typeSource, fnOwner);
			block.loopContext = loopContext;
			for(var v : localValues){
				block.addLocal(v.withoutRemoval());
			}
			guardedBlock(code, block);
			block.mergeBranch();
			
			if(block.terminates()){
				throw new MalformedJorth("lazyBlock must not terminate");
			}
			if(!block.stacksMatch(stack)){
				throw new MalformedJorth("lazyBlock must not change stack");
			}
			return block;
		}));
	}
	
	void defineLocalValue(String name, GenericType type, boolean canRemove) throws MalformedJorth{
		checkFreeze();
		Objects.requireNonNull(name);
		Objects.requireNonNull(type);
		if(localValues.has(name)){
			throw new MalformedJorth("Duplicated localValue: " + name);
		}
		int index = localValues.findSlot(type.getBaseType().slots);
		var local = new Local(name, type, index, canRemove);
		addLocal(local);
		debugLocals.put(local, new LocalDebugRange(insns.size()));
	}
	
	private void addLocal(Local local){
		localValues.add(local);
	}
	private void removeLocal(Local local){
		checkFreeze();
		localValues.remove(local);
		
		var debRange = debugLocals.get(local);
		if(debRange != null){
			debRange.end = insns.size();
		}
	}
	
	private CodeBlock createBlockFromHere(CodeArg code) throws MalformedJorth{
		return createBlockFromHere(code, loopContext);
	}
	private CodeBlock createBlockFromHere(CodeArg code, LoopContext context) throws MalformedJorth{
		var block = new CodeBlock(localStack.copyFlat(), typeSource, fnOwner);
		block.loopContext = context;
		for(var v : localValues){
			block.addLocal(v.withoutRemoval());
		}
		guardedBlock(code, block);
		block.mergeBranch();
		return block;
	}
	
	private void guardedBlock(CodeArg code, CodeBlock block) throws MalformedJorth{
		freezeReason = FreezeReason.EDITING_CHILD;
		try{
			code.accept(block);
		}finally{
			freezeReason = null;
		}
	}
	
	public CodeBlock get(Class<?> declaringClass, String fieldName) throws MalformedJorth{
		return get(ClassName.of(declaringClass), fieldName);
	}
	public CodeBlock get(ClassName declaringClass, String fieldName) throws MalformedJorth{
		preInsn();
		var type          = typeSource.byName(declaringClass);
		var accessorField = type.getField(fieldName);
		return get(accessorField);
	}
	
	public CodeBlock getThis(String fieldName) throws MalformedJorth{
		return getThis(fnOwner.owner().getField(fieldName));
	}
	public CodeBlock getThis(FieldInfo field) throws MalformedJorth{
		return get("this").get(field);
	}
	public CodeBlock get(FieldInfo field) throws MalformedJorth{
		preInsn();
		return add(GetFieldOp.simulate(localStack, typeSource, cName(), field));
	}
	
	public CodeBlock get(String localVal) throws MalformedJorth{
		preInsn();
		Local local = getLocal(localVal);
		return add(GetLocal.simulate(localStack, local));
	}
	
	private Local getLocal(String localVal) throws MalformedJorth{
		Local local = localValues.get(localVal);
		if(local == null){
			if(localVal.equals("this") && fnOwner.access().isStatic()){
				throw new MalformedJorth("Cannot use 'this' from a static function");
			}
			throw new MissingLocalField("Unknown localValue: " + localVal);
		}
		return local;
	}
	
	public CodeBlock val(Object val) throws MalformedJorth{
		return switch(val){
			case Integer v -> val((int)v);
			case Long v -> val((long)v);
			case Float v -> val((float)v);
			case Double v -> val((double)v);
			case Boolean v -> val((boolean)v);
			case String v -> val(v);
			case Class<?> v -> val(v);
			case ClassName v -> val(v);
			default -> throw new MalformedJorth("Invalid value: " + val);
		};
	}
	
	public CodeBlock val(int val) throws MalformedJorth{
		preInsn();
		return add(IVal.simulate(localStack, val));
	}
	public CodeBlock val(long val) throws MalformedJorth{
		preInsn();
		return add(LVal.simulate(localStack, val));
	}
	public CodeBlock val(float val) throws MalformedJorth{
		preInsn();
		return add(FVal.simulate(localStack, val));
	}
	public CodeBlock val(double val) throws MalformedJorth{
		preInsn();
		return add(DVal.simulate(localStack, val));
	}
	public CodeBlock val(boolean val) throws MalformedJorth{
		preInsn();
		return add(BVal.simulate(localStack, val));
	}
	public CodeBlock val(String val) throws MalformedJorth{
		preInsn();
		return add(StrVal.simulate(localStack, val));
	}
	public CodeBlock val(Class<?> val) throws MalformedJorth{
		preInsn();
		return val(ClassName.of(val));
	}
	public CodeBlock val(ClassName val) throws MalformedJorth{
		preInsn();
		return add(ClassVal.simulate(localStack, val));
	}
	
	public CodeBlock greaterThanOp() throws MalformedJorth{
		preInsn();
		return add(NumericComparison.simulate(localStack, true, false));
	}
	public CodeBlock greaterThanOrEqualOp() throws MalformedJorth{
		preInsn();
		return add(NumericComparison.simulate(localStack, true, true));
	}
	public CodeBlock lessThanOp() throws MalformedJorth{
		preInsn();
		return add(NumericComparison.simulate(localStack, false, false));
	}
	public CodeBlock lessThanOrEqualOp() throws MalformedJorth{
		preInsn();
		return add(NumericComparison.simulate(localStack, false, true));
	}
	
	public CodeBlock equalityOp() throws MalformedJorth{
		preInsn();
		return add(Equality.simulate(typeSource, localStack, true));
	}
	
	public void breakOp() throws MalformedJorth{
		loopJump(true);
	}
	public void continueOp() throws MalformedJorth{
		loopJump(false);
	}
	private void loopJump(boolean isBreak) throws MalformedJorth{
		preInsn();
		if(loopContext == null) throw new MalformedJorth((isBreak? "break" : "continue") + " requires an enclosing loop");
		loopContext.addJump(stackView(), isBreak);
		add(new LoopJump(loopContext, isBreak));
	}
	
	public void returnOp() throws MalformedJorth{
		preInsn();
		add(ReturnOp.simulate(fnOwner.returnType(), typeSource, localStack, true));
	}
	
	public void throwNew(Class<? extends Throwable> type) throws MalformedJorth{
		throwNew(type, args -> { });
	}
	public void throwNew(Class<? extends Throwable> type, String message) throws MalformedJorth{
		throwNew(type, args -> args.val(message));
	}
	public void throwNew(Class<? extends Throwable> type, CodeArg args) throws MalformedJorth{
		newObj(type, args).throwOp();
	}
	public void throwOp() throws MalformedJorth{
		preInsn();
		add(ThrowOp.simulate(typeSource, localStack));
	}
	public CodeBlock ifFalse(CodeArg code) throws MalformedJorth{
		return falseBlock(code, ConditionalJump.Type.TRUE_BOOL);
	}
	public CodeBlock ifTrue(CodeArg code) throws MalformedJorth{
		return trueBlock(code, ConditionalJump.Type.TRUE_BOOL);
	}
	public CodeBlock ifEquality(CodeArg code) throws MalformedJorth{
		return trueBlock(code, ConditionalJump.Type.EQUALITY);
	}
	public CodeBlock ifNotEquality(CodeArg code) throws MalformedJorth{
		return falseBlock(code, ConditionalJump.Type.EQUALITY);
	}
	public CodeBlock ifIsNull(CodeArg code) throws MalformedJorth{
		preInsn();
		var type = localStack.peekLast();
		return nullVal(type).ifEquality(code);
	}
	public CodeBlock ifIsNotNull(CodeArg code) throws MalformedJorth{
		preInsn();
		var type = localStack.peekLast();
		return nullVal(type).ifNotEquality(code);
	}
	private CodeBlock falseBlock(CodeArg code, ConditionalJump.Type type) throws MalformedJorth{
		preInsn();
		var vType = ConditionalJump.consumeCondition(localStack, typeSource, type);
		var block = createBlockFromHere(code);
		block.lockEditing();
		return add(ConditionalJump.simulate(this, type, vType, null, block));
	}
	private CodeBlock trueBlock(CodeArg code, ConditionalJump.Type type) throws MalformedJorth{
		preInsn();
		var vType = ConditionalJump.consumeCondition(localStack, typeSource, type);
		var block = createBlockFromHere(code);
		block.lockEditing();
		return add(ConditionalJump.simulate(this, type, vType, block, null));
	}
	public CodeBlock elseRun(CodeArg code) throws MalformedJorth{
		preInsn(false);
		var lastInsn = insns.isEmpty()? null : insns.getLast();
		if(!(lastInsn instanceof ConditionalJump jump)){
			throw new MalformedJorth("Must be run after a conditional operation");
		}
		if(jump.onFalse() != null){
			throw new MalformedJorth("Duplicate else call");
		}
		insns.removeLast();
		var block = createBlockFromHere(code);
		block.lockEditing();
		return add(jump.withFalse(this, block));
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
		return newObj(clazz, arguments, false);
	}
	CodeBlock newObj(ClassName clazz, CodeArg arguments, boolean enumConstant) throws MalformedJorth{
		preInsn();
		var type = GenericType.of(clazz);
		if(!enumConstant && type.dims() == 0 && typeSource.byName(clazz).type() == ClassType.ENUM){
			throw new MalformedJorth("Enum instances can only be created by enumConstant: " + clazz);
		}
		if(type.dims() != 0){
			var args = doArgs(arguments);
			if(args.stack.size() != type.dims()){
				throw new MalformedJorth(
					"Incorrect number of arguments for " + clazz + "\n" +
					"  Got:      " + args.stack.size() + "\n" +
					"  Expected: " + type.dims()
				);
			}
			return addWithArguments(NewOp.simulate(localStack, type, false), args.insnStart);
		}
		add(NewOp.simulate(localStack, type, true));
		var args = doArgs(arguments);
		var fn   = resolveFunction(typeSource.byName(clazz), "<init>", args.stack);
		return addWithArguments(InvokeOp.simulate(localStack, typeSource, cName(), fn, false), args.insnStart);
	}
	
	private void checkEnumConstructorCall(String name) throws MalformedJorth{
		if(fnOwner.isEnumConstructor() && name.equals("<init>")){
			throw new MalformedJorth("Explicit constructor calls are not supported in enum constructors");
		}
	}
	
	/**
	 * Calls a function on the last object on the stack with zero arguments (eg: toString)
	 *
	 * @param name function name
	 * @return this for chaining
	 * @throws MalformedJorth
	 */
	public CodeBlock call(String name) throws MalformedJorth{
		return call(name, 0);
	}
	/**
	 *
	 * This is a variant of the {@link #call(String, CodeArg)} without a lambda. You must specify the number of
	 * arguments the function will take and the caller will be the first next object on the stack before the arguments. <br>
	 * This style of calling is closer to the way bytecode is actually written out, but it is generally not preferable. <br>
	 * <code> body.get("this").val("myName").val("nickname").call("sayHelloTo", 2) </code> <br>
	 * Equivalent to the java code of: <br>
	 * <code> this.sayHelloTo(myName, nickname)</code>
	 *
	 * @param name          function name
	 * @param argumentCount number of arguments the calling function takes
	 * @return self for chaining
	 * @throws MalformedJorth
	 */
	public CodeBlock call(String name, int argumentCount) throws MalformedJorth{
		checkEnumConstructorCall(name);
		preInsn();
		var stackSize = localStack.size();
		var mark      = stackSize - argumentCount;
		var caller    = typeSource.byType(localStack.peek(mark - 1));
		
		List<JType> args = argumentCount == 0? List.of() : readCallStack(mark);
		
		FunctionInfo fn = resolveFunction(caller, name, args);
		return add(InvokeOp.simulate(localStack, typeSource, cName(), fn, false));
	}
	
	/**
	 * {@link #call(String, CodeArg)} utility overload for when all arguments are constants. Example: <br>
	 * <code>body.get("reader").call("readBytes", List.of(neededBytes))</code> <br>
	 * Equivalent to the java code of: <br>
	 * <code>reader.readBytes({ what ever int value neededBytes resolves to })</code>
	 *
	 * @param name      function name that will be called
	 * @param constants constant values that will be inserted on to the stack
	 * @return this for chaining
	 * @throws MalformedJorth
	 */
	public CodeBlock call(String name, List<Object> constants) throws MalformedJorth{
		return call(name, args -> {
			for(Object val : constants){
				args.val(val);
			}
		});
	}
	
	/**
	 * Calls a function on the instance of the last object on the stack. So... <br>
	 * <code> body.get("this").call("sayHelloTo", arg->arg.get("myName")) </code> <br>
	 * is equivalent to the java code of: <br>
	 * <code> this.sayHelloTo(myName)</code> <br>
	 * The argument gathering works trough the stack. Meaning, if you have
	 * [int, long, Foo] on the stack before the call(...), and after gatherArguments is done executing, there is
	 * [int, long, Foo, String, int] then that means that a function on Foo will be
	 * called with 2 arguments of String and int.
	 *
	 * @param name            function name that will be called
	 * @param gatherArguments lambda that collects the arguments for the function
	 * @return this for chaining
	 * @throws MalformedJorth
	 */
	public CodeBlock call(String name, CodeArg gatherArguments) throws MalformedJorth{
		checkEnumConstructorCall(name);
		preInsn();
		var caller = typeSource.byType(localStack.peekLast());
		
		var args = doArgs(gatherArguments);
		
		FunctionInfo fn = resolveFunction(caller, name, args.stack);
		return addWithArguments(InvokeOp.simulate(localStack, typeSource, cName(), fn, false), args.insnStart);
	}
	
	private CodeBlock addWithArguments(ArgumentInsn instruction, int argumentStart) throws MalformedJorth{
		return add(argumentStart<insns.size()? instruction.withArgumentStart(argumentStart) : instruction);
	}
	
	private record ArgsInfo(List<JType> stack, int insnStart){ }
	
	private ArgsInfo doArgs(CodeArg gatherArguments) throws MalformedJorth{
		var mark      = localStack.size();
		var insnStart = insns.size();
		gatherArguments.accept(this);
		mergeBranch();
		return new ArgsInfo(readCallStack(mark), insnStart);
	}
	
	public CodeBlock call(Class<?> staticCaller, String name) throws MalformedJorth{
		return call(ClassName.of(staticCaller), name);
	}
	public CodeBlock call(ClassName staticCaller, String name) throws MalformedJorth{
		return call(staticCaller, name, e -> { });
	}
	public CodeBlock call(Class<?> staticCaller, String name, CodeArg gatherArguments) throws MalformedJorth{
		return call(ClassName.of(staticCaller), name, gatherArguments);
	}
	public CodeBlock call(ClassName staticCaller, String name, CodeArg gatherArguments) throws MalformedJorth{
		checkEnumConstructorCall(name);
		preInsn();
		var cl = typeSource.byName(staticCaller);
		
		var args = doArgs(gatherArguments);
		
		var fn = cl.getFunction(new FunctionInfo.Signature(name, args.stack));
		
		return addWithArguments(InvokeOp.simulate(localStack, typeSource, cName(), fn, false), args.insnStart);
	}
	public CodeBlock call(Class<?> staticCaller, String name, int argumentCount) throws MalformedJorth{
		return call(ClassName.of(staticCaller), name, argumentCount);
	}
	public CodeBlock call(ClassName staticCaller, String name, int argumentCount) throws MalformedJorth{
		var stackSize = localStack.size();
		var mark      = stackSize - argumentCount;
		var cl        = typeSource.byName(staticCaller);
		
		List<JType> args = argumentCount == 0? List.of() : readCallStack(mark);
		
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
		checkEnumConstructorCall(fn.name());
		preInsn();
		return add(InvokeOp.simulate(localStack, typeSource, cName(), fn, false));
	}
	public CodeBlock call(FunctionDefinition fn, CodeArg gatherArguments) throws MalformedJorth{
		checkEnumConstructorCall(fn.name());
		preInsn();
		var args   = doArgs(gatherArguments);
		var fnArgs = fn.getArgs();
		if(args.stack.size() != fnArgs.size()){
			throw new MalformedJorth("Function argument takes " + fnArgs.size() + " but got " + args.stack.size());
		}
		return addWithArguments(InvokeOp.simulate(localStack, typeSource, cName(), fn, false), args.insnStart);
	}
	
	public CodeBlock set(Class<?> declaringClass, String fieldName) throws MalformedJorth{
		return set(ClassName.of(declaringClass), fieldName);
	}
	public CodeBlock set(ClassName declaringClass, String fieldName) throws MalformedJorth{
		var type = typeSource.byName(declaringClass);
		return setField(type.getField(fieldName));
	}
	
	public CodeBlock setThis(String fieldName) throws MalformedJorth{
		return setThis(fnOwner.owner().getField(fieldName));
	}
	public CodeBlock setThis(FieldInfo field) throws MalformedJorth{
		return get("this").swap().setField(field);
	}
	
	public CodeBlock set(FieldInfo field, int val) throws MalformedJorth      { return val(val).setField(field); }
	public CodeBlock set(FieldInfo field, long val) throws MalformedJorth     { return val(val).setField(field); }
	public CodeBlock set(FieldInfo field, float val) throws MalformedJorth    { return val(val).setField(field); }
	public CodeBlock set(FieldInfo field, double val) throws MalformedJorth   { return val(val).setField(field); }
	public CodeBlock set(FieldInfo field, boolean val) throws MalformedJorth  { return val(val).setField(field); }
	public CodeBlock set(FieldInfo field, String val) throws MalformedJorth   { return val(val).setField(field); }
	public CodeBlock set(FieldInfo field, Class<?> val) throws MalformedJorth { return val(val).setField(field); }
	public CodeBlock set(FieldInfo field, ClassName val) throws MalformedJorth{ return val(val).setField(field); }
	public CodeBlock setField(FieldInfo field) throws MalformedJorth{
		preInsn();
		return add(PutFieldOp.simulate(localStack, typeSource, cName(), field));
	}
	
	public CodeBlock set(String varName, int val) throws MalformedJorth      { return val(val).set(varName); }
	public CodeBlock set(String varName, long val) throws MalformedJorth     { return val(val).set(varName); }
	public CodeBlock set(String varName, float val) throws MalformedJorth    { return val(val).set(varName); }
	public CodeBlock set(String varName, double val) throws MalformedJorth   { return val(val).set(varName); }
	public CodeBlock set(String varName, boolean val) throws MalformedJorth  { return val(val).set(varName); }
	public CodeBlock set(String varName, String val) throws MalformedJorth   { return val(val).set(varName); }
	public CodeBlock set(String varName, Class<?> val) throws MalformedJorth { return val(val).set(varName); }
	public CodeBlock set(String varName, ClassName val) throws MalformedJorth{ return val(val).set(varName); }
	public CodeBlock set(String varName) throws MalformedJorth{
		preInsn();
		Local local = getLocal(varName);
		return add(PutLocalVarOp.simulate(localStack, typeSource, local));
	}
	
	public CodeBlock pop() throws MalformedJorth{
		preInsn();
		return add(PopOp.simulate(localStack));
	}
	public CodeBlock dup() throws MalformedJorth{
		preInsn();
		return add(DupOp.simulate(localStack));
	}
	public CodeBlock swap() throws MalformedJorth{
		preInsn();
		return add(SwapOp.simulate(localStack));
	}
	
	void enumSuper(String name, String ordinal) throws MalformedJorth{
		callSuperInternal(b -> b.get(name).get(ordinal));
		removeLocal(getLocal(name));
		removeLocal(getLocal(ordinal));
	}
	public CodeBlock callSuper(CodeArg gatherArguments) throws MalformedJorth{
		if(fnOwner.isEnumConstructor()) throw new MalformedJorth("Enum constructor superclass call is supplied by Jorth");
		return callSuperInternal(gatherArguments);
	}
	private CodeBlock callSuperInternal(CodeArg gatherArguments) throws MalformedJorth{
		preInsn();
		get("this");
		var          args    = doArgs(gatherArguments);
		FunctionInfo superFn = resolveFunction(fnOwner.owner().superType(), fnOwner.name(), args.stack);
		addWithArguments(InvokeOp.simulate(localStack, typeSource, cName(), superFn, true), args.insnStart);
		if(fnOwner.isConstructor()){
			fnOwner.ranInstanceInitializers = true;
			fnOwner.owner().initializeInstanceFields(this);
		}
		return this;
	}
	/**
	 * Calls super of the current method or constructor. Automatically passes all arguments in declaration order.
	 *
	 * @return
	 * @throws MalformedJorth
	 */
	public CodeBlock callSuperAutoPass() throws MalformedJorth{
		if(fnOwner.isEnumConstructor()) throw new MalformedJorth("Enum constructor superclass call is supplied by Jorth");
		preInsn();
		get("this");
		var argumentStart = insns.size();
		for(String argName : fnOwner.getArgNames()){
			get(argName);
		}
		FunctionInfo superFn = resolveFunction(fnOwner.owner().superType(), fnOwner.name(), fnOwner.getArgs());
		addWithArguments(InvokeOp.simulate(localStack, typeSource, cName(), superFn, true), argumentStart);
		if(fnOwner.isConstructor()){
			fnOwner.ranInstanceInitializers = true;
			fnOwner.owner().initializeInstanceFields(this);
		}
		return this;
	}
	
	private ClassName cName(){
		return fnOwner.owner().name();
	}
	
	void visitConstructorPrefix(MethodVisitor writer) throws MalformedJorth{
		var prefix = new CodeBlock(null, typeSource, fnOwner);
		prefix.addLocal(getLocal("this").withoutRemoval());
		for(String argName : fnOwner.getArgNames()){
			prefix.addLocal(getLocal(argName).withoutRemoval());
		}
		prefix.get("this");
		var superFn = resolveFunction(fnOwner.owner().superType(), "<init>", List.of());
		prefix.add(InvokeOp.simulate(prefix.localStack, typeSource, cName(), superFn, true));
		fnOwner.owner().initializeInstanceFields(prefix);
		prefix.visit(writer);
	}
	
	private static final class InstructionLines extends MethodVisitor{
		private record Line(int number, Label start){ }
		
		private final List<Line> lines = new ArrayList<>();
		
		InstructionLines(MethodVisitor writer){
			super(Opcodes.ASM9, writer);
		}
		void instruction(Label start){
			lines.add(new Line(lines.size() + 1, start));
		}
		void finish(Label end){
			for(var line : lines){
				// Empty structural instructions at the end have no bytecode to map.
				if(line.start.getOffset()<end.getOffset()) visitLineNumber(line.number, line.start);
			}
		}
	}
	
	public void visit(MethodVisitor fn) throws MalformedJorth{
		visit(fn, false, null);
	}
	void visit(MethodVisitor fn, boolean constructorPrefix, ReturnOp implicitReturn) throws MalformedJorth{
		if(fnOwner.owner().debugEmitLevel() == ClassDefinition.DebugEmitLevel.NONE){
			if(constructorPrefix){
				visitConstructorPrefix(fn);
			}
			for(var insn : insns){
				insn.visit(fn);
			}
			if(implicitReturn != null) implicitReturn.visit(fn);
			return;
		}
		
		// Instructions count as lines
		boolean root  = !(fn instanceof InstructionLines);
		var     lines = root? new InstructionLines(fn) : (InstructionLines)fn;
		fn = lines;
		
		var boundaries = new Label[insns.size() + 1];
		for(int i = 0; i<boundaries.length; i++){
			boundaries[i] = new Label();
		}
		
		fn.visitLabel(boundaries[0]);
		var bodyStart = boundaries[0];
		if(constructorPrefix){
			visitConstructorPrefix(fn);
			bodyStart = new Label();
			fn.visitLabel(bodyStart);
		}
		for(int i = 0; i<insns.size(); i++){
			if(i != 0) fn.visitLabel(boundaries[i]);
			lines.instruction(i == 0? bodyStart : boundaries[i]);
			insns.get(i).visit(fn);
		}
		if(insns.size() != 0){
			fn.visitLabel(boundaries[insns.size()]);
		}
		if(implicitReturn != null){
			lines.instruction(insns.isEmpty()? bodyStart : boundaries[insns.size()]);
			implicitReturn.visit(fn);
		}
		
		var end = new Label();
		fn.visitLabel(end);
		if(root){
			lines.finish(end);
		}
		
		var iter = debugLocals.entrySet().stream().sorted(
			Comparator.<Map.Entry<Local, LocalDebugRange>>comparingInt(e -> e.getKey().index())
			          .thenComparing(e -> e.getKey().name())
		).iterator();
		while(iter.hasNext()){
			var entry = iter.next();
			var local = entry.getKey();
			var range = entry.getValue();
			
			var startLabel = boundaries[range.start];
			var endLabel   = range.end == -1? end : boundaries[range.end];
			if(startLabel.getOffset() == endLabel.getOffset()) continue;
			
			var descriptor = local.type().jvmDescriptorStr();
			var signature  = local.type().jvmSignatureStr();
			fn.visitLocalVariable(
				local.name(), descriptor, descriptor.equals(signature)? null : signature,
				startLabel, endLabel, local.index()
			);
		}
	}
	ReturnOp prepareImplicitReturn() throws MalformedJorth{
		if(terminates()) return null;
		try{
			checkFreeze();
			return ReturnOp.simulate(fnOwner.returnType(), typeSource, localStack, false);
		}catch(MalformedJorth e){
			throw new MalformedJorth("Failed to return on " + fnOwner, e);
		}
	}
	
	private FunctionInfo resolveFunction(ClassInfo cInfo, String functionName, List<JType> args) throws MalformedJorth{
		try{
			return cInfo.getFunction(new FunctionInfo.Signature(functionName, args));
		}catch(MalformedJorth e){
			var candidates = cInfo.getFunctionsByName(functionName).iterator();
			candidates:
			while(candidates.hasNext()){
				var candidate = candidates.next();
				var argsF     = candidate.argumentTypes();
				if(argsF.size() != args.size()) continue;
				for(int i = 0; i<argsF.size(); i++){
					if(!args.get(i).asGeneric().instanceOf(typeSource, argsF.get(i).asGeneric())){
						continue candidates;
					}
				}
				return candidate;
			}
			throw e;
		}
	}
	
	public boolean terminates(){
		if(insns.isEmpty()) return false;
		Insn last = insns.getLast();
		return last instanceof TerminatingInsn t && t.terminates();
	}
	
	@Override
	protected CodeBlock clone() throws CloneNotSupportedException{
		CodeBlock cloned = new CodeBlock(this.localStack.clone(), this.typeSource, this.fnOwner);
		localValues.forEach(cloned::addLocal);
		cloned.insns.addAll(this.insns);
		cloned.loopContext = loopContext;
		return cloned;
	}
	public boolean stacksMatch(CodeBlock other){
		return stacksMatch(other.localStack);
	}
	public boolean stacksMatch(TypeStack localStack){
		return this.localStack.equals(localStack);
	}
	
	public List<GenericType> stackView(){
		return localStack.totalStack().toList();
	}
	
	public CodeBlock cast(Class<?> type) throws MalformedJorth { return cast(GenericType.of(type)); }
	public CodeBlock cast(ClassName type) throws MalformedJorth{ return cast(GenericType.of(type)); }
	public CodeBlock cast(GenericType type) throws MalformedJorth{
		preInsn();
		return add(CastOp.simulate(localStack, typeSource, type));
	}
	public CodeBlock setArrayElement() throws MalformedJorth{
		preInsn();
		return add(PutElementOp.simulate(localStack, typeSource));
	}
	public CodeBlock getArrayElement() throws MalformedJorth{
		preInsn();
		return add(GetElementOp.simulate(localStack, typeSource));
	}
	
	public CodeBlock callVirtual(Consumer<BootstrapFn> bootstrap, Consumer<CallingFn> fnDef, CodeArg arguments) throws MalformedJorth{
		preInsn();
		var args = doArgs(arguments);
		
		var boot = new BootstrapFn();
		var fn   = new CallingFn();
		bootstrap.accept(boot);
		fnDef.accept(fn);
		
		if(boot.owner == null){
			throw new MalformedJorth("Bootstrap function must be specified");
		}
		if(fn.name == null){
			throw new MalformedJorth("Calling function name must be specified");
		}
		
		return addWithArguments(VirtualCallOp.simulate(localStack, typeSource, boot, fn, args.stack), args.insnStart);
	}
	
	public CodeBlock var(Class<?> type, String name) throws MalformedJorth{
		return var(GenericType.of(type), name);
	}
	public CodeBlock var(GenericType type, String name) throws MalformedJorth{
		defineLocalValue(name, type, true);
		return this;
	}
	public CodeBlock forgetVar(String name) throws MalformedJorth{
		Local local = localValues.getForce(name);
		if(!local.canRemove()){
			throw new MalformedJorth("Cannot remove local variable " + name);
		}
		removeLocal(local);
		return this;
	}
	public boolean hasVar(String name){
		return localValues.get(name) != null;
	}
	public CodeBlock setIntoNewVar(String name) throws MalformedJorth{
		preInsn();
		var type = localStack.peekLast();
		return var(type, name).set(name);
	}
	
	public CodeBlock inc(String var) throws MalformedJorth{
		return inc(var, 1);
	}
	public CodeBlock inc(String var, int val) throws MalformedJorth{
		return get(var).add(val).set(var);
	}
	private CodeBlock arithmetic(ArithmeticOp operation, Number constant) throws MalformedJorth{
		preInsn();
		return add(Arithmetic.simulate(localStack, operation, constant));
	}
	
	/**
	 * Pops 2 numbers from stack, adds and pushes result back
	 */
	public CodeBlock add() throws MalformedJorth{ return arithmetic(ArithmeticOp.ADD, null); }
	/**
	 * Pops one number from the stack and adds it with the constant, then pushes result to stack
	 */
	public CodeBlock add(int val) throws MalformedJorth{ return arithmetic(ArithmeticOp.ADD, val); }
	public CodeBlock add(long val) throws MalformedJorth  { return arithmetic(ArithmeticOp.ADD, val); }
	public CodeBlock add(float val) throws MalformedJorth { return arithmetic(ArithmeticOp.ADD, val); }
	public CodeBlock add(double val) throws MalformedJorth{ return arithmetic(ArithmeticOp.ADD, val); }
	
	/**
	 * Pops 2 numbers from stack, subtracts and pushes result back
	 */
	public CodeBlock sub() throws MalformedJorth{ return arithmetic(ArithmeticOp.SUB, null); }
	/**
	 * Pops one number from the stack and substracts it with the constant, then pushes result to stack
	 */
	public CodeBlock sub(int val) throws MalformedJorth{ return arithmetic(ArithmeticOp.SUB, val); }
	public CodeBlock sub(long val) throws MalformedJorth  { return arithmetic(ArithmeticOp.SUB, val); }
	public CodeBlock sub(float val) throws MalformedJorth { return arithmetic(ArithmeticOp.SUB, val); }
	public CodeBlock sub(double val) throws MalformedJorth{ return arithmetic(ArithmeticOp.SUB, val); }
	
	/**
	 * Pops 2 numbers from stack, multiplies and pushes result back
	 */
	public CodeBlock mul() throws MalformedJorth{ return arithmetic(ArithmeticOp.MUL, null); }
	/**
	 * Pops one number from the stack and multiplies it with the constant, then pushes result to stack
	 */
	public CodeBlock mul(int val) throws MalformedJorth{ return arithmetic(ArithmeticOp.MUL, val); }
	public CodeBlock mul(long val) throws MalformedJorth  { return arithmetic(ArithmeticOp.MUL, val); }
	public CodeBlock mul(float val) throws MalformedJorth { return arithmetic(ArithmeticOp.MUL, val); }
	public CodeBlock mul(double val) throws MalformedJorth{ return arithmetic(ArithmeticOp.MUL, val); }
	
	/**
	 * Pops 2 numbers from stack, divides and pushes result back
	 */
	public CodeBlock div() throws MalformedJorth{ return arithmetic(ArithmeticOp.DIV, null); }
	/**
	 * Pops one number from the stack and divides it with the constant, then pushes result to stack
	 */
	public CodeBlock div(int val) throws MalformedJorth{ return arithmetic(ArithmeticOp.DIV, val); }
	public CodeBlock div(long val) throws MalformedJorth  { return arithmetic(ArithmeticOp.DIV, val); }
	public CodeBlock div(float val) throws MalformedJorth { return arithmetic(ArithmeticOp.DIV, val); }
	public CodeBlock div(double val) throws MalformedJorth{ return arithmetic(ArithmeticOp.DIV, val); }
	
	public CodeBlock mod() throws MalformedJorth          { return arithmetic(ArithmeticOp.MOD, null); }
	public CodeBlock mod(int val) throws MalformedJorth   { return arithmetic(ArithmeticOp.MOD, val); }
	public CodeBlock mod(long val) throws MalformedJorth  { return arithmetic(ArithmeticOp.MOD, val); }
	public CodeBlock mod(float val) throws MalformedJorth { return arithmetic(ArithmeticOp.MOD, val); }
	public CodeBlock mod(double val) throws MalformedJorth{ return arithmetic(ArithmeticOp.MOD, val); }
	
	/**
	 * Negates the number on the stack
	 */
	public CodeBlock neg() throws MalformedJorth{ return arithmetic(ArithmeticOp.NEG, null); }
	
	public CodeBlock bitShiftRight(boolean logical, int val) throws MalformedJorth{
		return val(val).bitShiftRight(logical);
	}
	public CodeBlock bitShiftRight(boolean logical) throws MalformedJorth{
		preInsn();
		return add(BitShiftRight.simulate(localStack, logical));
	}
	public CodeBlock bitShiftLeft(int val) throws MalformedJorth{
		return val(val).bitShiftLeft();
	}
	public CodeBlock bitShiftLeft() throws MalformedJorth{
		preInsn();
		return add(BitShiftLeft.simulate(localStack));
	}
	public CodeBlock bitAnd(int val) throws MalformedJorth{
		preInsn();
		if(localStack.peekLast().equals(GenericType.LONG)){
			val((long)val);
		}else{
			val(val);
		}
		return bitAnd();
	}
	public CodeBlock bitAnd(long val) throws MalformedJorth{
		preInsn();
		if(localStack.peekLast().equals(GenericType.LONG)){
			val(val);
		}else{
			val(Math.toIntExact(val));
		}
		return bitAnd();
	}
	public CodeBlock bitAnd() throws MalformedJorth{
		preInsn();
		return add(BitAnd.simulate(localStack));
	}
	public CodeBlock bitOr() throws MalformedJorth{
		preInsn();
		return add(BitOr.simulate(localStack));
	}
	
	public CodeBlock nullVal(Class<?> type) throws MalformedJorth { return nullVal(ClassName.of(type)); }
	public CodeBlock nullVal(ClassName type) throws MalformedJorth{ return nullVal(GenericType.of(type)); }
	public CodeBlock nullVal(GenericType type) throws MalformedJorth{
		preInsn();
		return add(NullConstant.simulate(localStack, type));
	}
	
	public CodeBlock unbox() throws MalformedJorth{
		preInsn();
		class Boxes{
			private static Map.Entry<GenericType, FunctionInfo> getInfo(Class<?> boxTyp, String fnName){
				var          cInfo = new ClassInfo.OfClass(TypeSource.of(null, boxTyp.getClassLoader()), boxTyp);
				FunctionInfo info;
				try{
					info = cInfo.getFunction(new FunctionInfo.Signature(fnName, List.of()));
				}catch(MalformedJorth e){
					throw new RuntimeException(e);
				}
				return Map.entry(GenericType.of(boxTyp), info);
			}
			private static final Map<GenericType, FunctionInfo> MAP = Map.ofEntries(
				getInfo(Boolean.class, "booleanValue"),
				getInfo(Byte.class, "byteValue"),
				getInfo(Short.class, "shortValue"),
				getInfo(Integer.class, "intValue"),
				getInfo(Long.class, "longValue"),
				getInfo(Character.class, "charValue"),
				getInfo(Float.class, "floatValue"),
				getInfo(Double.class, "doubleValue")
			);
		}
		
		var typ = localStack.peekLast();
		
		FunctionInfo boxFn = Boxes.MAP.get(typ);
		if(boxFn == null){
			throw new MalformedJorth("Cannot unbox type: " + typ);
		}
		return add(InvokeOp.simulate(localStack, typeSource, cName(), boxFn, false));
	}
	public CodeBlock box() throws MalformedJorth{
		preInsn();
		class Boxes{
			private static Map.Entry<GenericType, FunctionInfo> getBoxInfo(GenericType typ, Class<?> cls){
				var          cInfo = new ClassInfo.OfClass(TypeSource.of(null, cls.getClassLoader()), cls);
				FunctionInfo info;
				try{
					info = cInfo.getFunction(new FunctionInfo.Signature("valueOf", List.of(typ)));
				}catch(MalformedJorth e){
					throw new RuntimeException(e);
				}
				return Map.entry(typ, info);
			}
			private static final Map<GenericType, FunctionInfo> MAP = Map.ofEntries(
				getBoxInfo(GenericType.BOOL, Boolean.class),
				getBoxInfo(GenericType.BYTE, Byte.class),
				getBoxInfo(GenericType.SHORT, Short.class),
				getBoxInfo(GenericType.INT, Integer.class),
				getBoxInfo(GenericType.LONG, Long.class),
				getBoxInfo(GenericType.CHAR, Character.class),
				getBoxInfo(GenericType.FLOAT, Float.class),
				getBoxInfo(GenericType.DOUBLE, Double.class)
			);
		}
		
		var typ = localStack.peekLast();
		
		FunctionInfo boxFn = Boxes.MAP.get(typ);
		if(boxFn == null){
			throw new MalformedJorth("Cannot box non-primitive type: " + typ);
		}
		return add(InvokeOp.simulate(localStack, typeSource, cName(), boxFn, false));
	}
	
	@Override
	public String toString(){
		return insns.reversed().stream().limit(10).toList().reversed().stream().map(Object::toString).collect(Collectors.joining("\n"));
	}
	
	public void lockEditing(){
		checkFreeze();
		freezeReason = FreezeReason.FINISHED_EDITING;
	}
	FreezeReason getFreezeReason(){
		return freezeReason;
	}
	
	public String print(){
		var out = new DefinitionPrinter();
		print(out);
		return out.toString();
	}
	
	void print(DefinitionPrinter out){
		var declarations = debugLocals.entrySet().stream()
		                              .filter(e -> e.getKey().canRemove())
		                              .sorted(Comparator.<Map.Entry<Local, LocalDebugRange>>comparingInt(e -> e.getValue().start)
		                                                .thenComparingInt(e -> e.getKey().index())
		                                                .thenComparing(e -> e.getKey().name()))
		                              .iterator();
		
		var declaration = declarations.hasNext()? declarations.next() : null;
		
		var openings = new HashMap<Integer, List<Integer>>();
		for(int end = 0; end<insns.size(); end++){
			if(insns.get(end) instanceof ArgumentInsn instruction && instruction.argumentStart()>=0){
				openings.computeIfAbsent(instruction.argumentStart(), ignored -> new ArrayList<>()).add(end);
			}
		}
		openings.values().forEach(ends -> ends.sort(Comparator.reverseOrder()));
		
		for(int i = 0; i<=insns.size(); i++){
			for(int end : openings.getOrDefault(i, List.of())){
				out.open(switch(insns.get(end)){
					case InvokeOp call -> InstructionPrinter.callHeader(call);
					case VirtualCallOp call -> InstructionPrinter.callHeader(call);
					case NewOp array -> DefinitionPrinter.keyword("new") + " " + DefinitionPrinter.type(array.type());
					default -> throw new IllegalStateException("Unsupported argument block");
				});
				if(insns.get(end) instanceof VirtualCallOp call){
					InstructionPrinter.printBootstrap(call, out);
				}
			}
			
			while(declaration != null && declaration.getValue().start == i){
				var local = declaration.getKey();
				out.line(DefinitionPrinter.keyword("var") + " " + DefinitionPrinter.type(local.type()) + " " + DefinitionPrinter.name(local.name()) + ";");
				declaration = declarations.hasNext()? declarations.next() : null;
			}
			
			if(i<insns.size()){
				var instruction = insns.get(i);
				if(instruction instanceof ArgumentInsn args && args.argumentStart()>=0){
					out.close();
				}else instruction.print(out);
			}
		}
	}
}
