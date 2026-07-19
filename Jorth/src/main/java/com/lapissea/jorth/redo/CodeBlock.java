package com.lapissea.jorth.redo;

import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.exceptions.MissingLocalField;
import com.lapissea.jorth.lang.ClassName;
import com.lapissea.jorth.lang.FunctionInfo;
import com.lapissea.jorth.lang.LocalsArray;
import com.lapissea.jorth.lang.LocalsArray.Local;
import com.lapissea.jorth.lang.type.ClassInfo;
import com.lapissea.jorth.lang.type.FieldInfo;
import com.lapissea.jorth.lang.type.GenericType;
import com.lapissea.jorth.lang.type.JType;
import com.lapissea.jorth.lang.type.TypeSource;
import com.lapissea.jorth.lang.type.TypeStack;
import com.lapissea.jorth.redo.Insn.*;
import org.objectweb.asm.MethodVisitor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class CodeBlock{
	
	private final LocalsArray localValues = new LocalsArray();
	
	private final List<Insn> insns = new ArrayList<>();
	private final TypeStack  localStack;
	
	private final TypeSource         typeSource;
	private final FunctionDefinition fnOwner;
	
	private boolean addingChild;
	
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
		if(addingChild){
			throw new IllegalAccessError("Should not modify parent while in a lambda");
		}
		if(terminates()){
			throw new MalformedJorth("This code block has terminated");
		}
		// null means NOOP
		if(insn != null) insns.add(insn);
		return this;
	}
	
	public CodeBlock scope(CodeArg code) throws MalformedJorth{
		var block = createBlockFromHere(code);
		for(GenericType typ : block.localStack.getLocalPortion()){
			localStack.push(typ);
		}
		return add(InlineBlock.simulate(block));
	}
	
	void defineLocalValue(String name, GenericType type, boolean canRemove) throws MalformedJorth{
		Objects.requireNonNull(name);
		Objects.requireNonNull(type);
		if(localValues.has(name)){
			throw new MalformedJorth("Duplicated localValue: " + name);
		}
		int index = localValues.findSlot(type.getBaseType().slots);
		addLocal(new Local(name, type, index, canRemove));
	}
	
	private void addLocal(Local local){
		localValues.add(local);
	}
	private void removeLocal(Local local){
		localValues.remove(local);
	}
	
	private CodeBlock createBlockFromHere(CodeArg code) throws MalformedJorth{
		var block = new CodeBlock(localStack, typeSource, fnOwner);
		for(var v : localValues){
			block.addLocal(v.withoutRemoval());
		}
		guardedBlock(code, block);
		return block;
	}
	
	private void guardedBlock(CodeArg code, CodeBlock block) throws MalformedJorth{
		addingChild = true;
		try{
			code.accept(block);
		}finally{
			addingChild = false;
		}
	}
	
	public CodeBlock get(Class<?> declaringClass, String fieldName) throws MalformedJorth{
		return get(ClassName.of(declaringClass), fieldName);
	}
	public CodeBlock get(ClassName declaringClass, String fieldName) throws MalformedJorth{
		var type          = typeSource.byName(declaringClass);
		var accessorField = type.getField(fieldName);
		return get(accessorField);
	}
	
	public CodeBlock getThis(FieldInfo field) throws MalformedJorth{
		return get("this").get(field);
	}
	public CodeBlock get(FieldInfo field) throws MalformedJorth{
		return add(GetFieldOp.simulate(localStack, typeSource, field));
	}
	
	public CodeBlock get(String localVal) throws MalformedJorth{
		doGetLocal(localVal);
		return this;
	}
	
	private void doGetLocal(String localVal) throws MalformedJorth{
		Local local = getLocal(localVal);
		add(GetLocal.simulate(localStack, local));
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
	
	public CodeBlock val(int val) throws MalformedJorth      { return add(IVal.simulate(localStack, val)); }
	public CodeBlock val(long val) throws MalformedJorth     { return add(LVal.simulate(localStack, val)); }
	public CodeBlock val(float val) throws MalformedJorth    { return add(FVal.simulate(localStack, val)); }
	public CodeBlock val(double val) throws MalformedJorth   { return add(DVal.simulate(localStack, val)); }
	public CodeBlock val(boolean val) throws MalformedJorth  { return add(BVal.simulate(localStack, val)); }
	public CodeBlock val(String val) throws MalformedJorth   { return add(StrVal.simulate(localStack, val)); }
	public CodeBlock val(Class<?> val) throws MalformedJorth { return val(ClassName.of(val)); }
	public CodeBlock val(ClassName val) throws MalformedJorth{ return add(ClassVal.simulate(localStack, val)); }
	
	public CodeBlock equalityOp() throws MalformedJorth{
		return add(Equality.simulate(typeSource, localStack, true));
	}
	
	public void returnOp() throws MalformedJorth{
		add(ReturnOp.simulate(fnOwner.returnType(), typeSource, localStack, true));
	}
	public void throwOp() throws MalformedJorth{
		add(ThrowOp.simulate(typeSource, localStack));
	}
	public CodeBlock ifFalse(CodeArg code) throws MalformedJorth{
		return falseBlock(code, ConditionalJump.Type.TRUE_BOOL);
	}
	public CodeBlock ifTrue(CodeArg code) throws MalformedJorth{
		return trueBlock(code, ConditionalJump.Type.TRUE_BOOL);
	}
	public CodeBlock ifEquality(CodeArg code) throws MalformedJorth{
		return equalityOp().ifTrue(code);
//		return trueBlock(code, ConditionalJump.Type.EQUALITY);
	}
	private CodeBlock falseBlock(CodeArg code, ConditionalJump.Type type) throws MalformedJorth{
		var block = createBlockFromHere(code);
		return add(ConditionalJump.simulate(localStack, typeSource, type, null, block));
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
	
	public CodeBlock call(Class<?> staticCaller, String name) throws MalformedJorth{
		return call(ClassName.of(staticCaller), name, e -> { });
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
	
	public CodeBlock set(Class<?> declaringClass, String fieldName) throws MalformedJorth{
		return set(ClassName.of(declaringClass), fieldName);
	}
	public CodeBlock set(ClassName declaringClass, String fieldName) throws MalformedJorth{
		var type = typeSource.byName(declaringClass);
		return set(type.getField(fieldName));
	}
	
	public CodeBlock setThis(FieldInfo field) throws MalformedJorth{
		return get("this").swap().set(field);
	}
	
	public CodeBlock set(FieldInfo field, int val) throws MalformedJorth      { return val(val).set(field); }
	public CodeBlock set(FieldInfo field, long val) throws MalformedJorth     { return val(val).set(field); }
	public CodeBlock set(FieldInfo field, float val) throws MalformedJorth    { return val(val).set(field); }
	public CodeBlock set(FieldInfo field, double val) throws MalformedJorth   { return val(val).set(field); }
	public CodeBlock set(FieldInfo field, boolean val) throws MalformedJorth  { return val(val).set(field); }
	public CodeBlock set(FieldInfo field, String val) throws MalformedJorth   { return val(val).set(field); }
	public CodeBlock set(FieldInfo field, Class<?> val) throws MalformedJorth { return val(val).set(field); }
	public CodeBlock set(FieldInfo field, ClassName val) throws MalformedJorth{ return val(val).set(field); }
	public CodeBlock set(FieldInfo field) throws MalformedJorth{
		return add(PutFieldOp.simulate(localStack, typeSource, field));
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
		Local local = getLocal(varName);
		return add(PutLocalVarOp.simulate(localStack, typeSource, local));
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
	
	public CodeBlock callSuper(CodeArg gatherArguments) throws MalformedJorth{
		get("this");
		var          args    = doArgs(gatherArguments);
		FunctionInfo superFn = resolveFunction(fnOwner.owner().superType(), fnOwner.name(), args);
		return add(InvokeOp.simulate(localStack, typeSource, cName(), superFn, true));
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
		localValues.forEach(cloned::addLocal);
		cloned.insns.addAll(this.insns);
		return cloned;
	}
	public boolean stacksMatch(CodeBlock other){
		return stacksMatch(other.localStack);
	}
	public boolean stacksMatch(TypeStack localStack){
		return this.localStack.equals(localStack);
	}
	
	public CodeBlock cast(Class<?> type) throws MalformedJorth { return cast(ClassName.of(type)); }
	public CodeBlock cast(ClassName type) throws MalformedJorth{ return cast(GenericType.of(type)); }
	public CodeBlock cast(GenericType type) throws MalformedJorth{
		return add(CastOp.simulate(localStack, typeSource, type));
	}
	public CodeBlock setArrayElement() throws MalformedJorth{
		return add(PutElementOp.simulate(localStack, typeSource));
	}
	public CodeBlock getArrayElement() throws MalformedJorth{
		return add(GetElementOp.simulate(localStack, typeSource));
	}
	
	public CodeBlock callVirtual(Consumer<BootstrapFn> bootstrap, Consumer<CallingFn> fnDef, CodeArg arguments) throws MalformedJorth{
		List<JType> args = doArgs(arguments);
		
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
		
		return add(VirtualCallOp.simulate(localStack, typeSource, boot, fn, args));
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
	
	public CodeBlock add(int val) throws MalformedJorth{
		return add(Increment.simulate(localStack, val));
	}
	public CodeBlock add(double val) throws MalformedJorth{
		return add(Increment.simulate(localStack, val));
	}
	
	public CodeBlock bitShiftRight(boolean logical, int val) throws MalformedJorth{
		return val(val).bitShiftRight(logical);
	}
	public CodeBlock bitShiftRight(boolean logical) throws MalformedJorth{
		return add(BitShiftRight.simulate(localStack, logical));
	}
	public CodeBlock bitShiftLeft(int val) throws MalformedJorth{
		return val(val).bitShiftLeft();
	}
	public CodeBlock bitShiftLeft() throws MalformedJorth{
		return add(BitShiftLeft.simulate(localStack));
	}
	public CodeBlock bitAnd(int val) throws MalformedJorth{
		if(localStack.peekLast().equals(GenericType.LONG)){
			val((long)val);
		}else{
			val(val);
		}
		return bitAnd();
	}
	public CodeBlock bitAnd(long val) throws MalformedJorth{
		if(localStack.peekLast().equals(GenericType.LONG)){
			val(val);
		}else{
			val(Math.toIntExact(val));
		}
		return bitAnd();
	}
	public CodeBlock bitAnd() throws MalformedJorth{
		return add(BitAnd.simulate(localStack));
	}
	
	public CodeBlock nullVal(Class<?> type) throws MalformedJorth { return nullVal(ClassName.of(type)); }
	public CodeBlock nullVal(ClassName type) throws MalformedJorth{ return nullVal(GenericType.of(type)); }
	public CodeBlock nullVal(GenericType type) throws MalformedJorth{
		return add(NullConstant.simulate(localStack, type));
	}
	
	public CodeBlock box() throws MalformedJorth{
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
	
}
