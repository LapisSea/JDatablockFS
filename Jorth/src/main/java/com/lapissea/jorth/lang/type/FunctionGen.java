package com.lapissea.jorth.lang.type;

import com.lapissea.jorth.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import com.lapissea.jorth.lang.Endable;
import com.lapissea.jorth.lang.info.FunctionInfo;
import com.lapissea.util.NotImplementedException;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import java.util.*;

import static org.objectweb.asm.Opcodes.*;

public final class FunctionGen implements Endable, FunctionInfo{
	
	public record ArgInfo(GenericType type, String name){ }
	
	private record Arg(GenericType type, GenericType.BaseType info, String name, int accessIndex){ }
	
	private class CodePath{
		private final TypeStack stack;
		
		private boolean ended;
		
		private Label endLabel = new Label();
		
		public CodePath(MethodVisitor mv, TypeStack stack){
			this.stack = stack;
		}
		
		public void loadThisIns(){
			writer.visitVarInsn(ALOAD, 0);
			stack.push(new GenericType(owner.name));
		}
		
		public void getFieldIns(FieldInfo field) throws MalformedJorth{
			var owner = field.owner();
			var type  = field.type();
			
			if(!field.isStatic()){
				var stackOwnerType = stack.pop().raw();
				if(!stackOwnerType.instanceOf(typeSource, owner)){
					throw new ClassCastException(stackOwnerType + " not compatible with " + owner);
				}
			}
			
			writer.visitFieldInsn(field.isStatic()? GETSTATIC : GETFIELD, owner.slashed(), field.name(), type.jvmSignature().toString());
			stack.push(type);
		}
		
		public void setFieldIns(FieldInfo field) throws MalformedJorth{
			var owner = field.owner();
			var type  = field.type();
			
			stack.requireElements(field.isStatic()? 1 : 2);
			
			var valueType = stack.pop();
			
			if(!valueType.instanceOf(typeSource, type)){
				throw new ClassCastException(valueType + " not compatible with " + type);
			}
			
			if(!field.isStatic()){
				var stackOwnerType = stack.pop().raw();
				if(!stackOwnerType.instanceOf(typeSource, owner)){
					throw new ClassCastException(stackOwnerType + " not compatible with " + owner);
				}
			}
			
			writer.visitFieldInsn(field.isStatic()? PUTSTATIC : PUTFIELD, owner.slashed(), field.name(), type.jvmSignature().toString());
		}
		
		public void loadArgumentIns(Arg arg){
			writer.visitVarInsn(arg.info.loadOp(), arg.accessIndex());
			stack.push(arg.type);
		}
		
		public void end() throws MalformedJorth{
			if(ended) return;
			ended = true;
			
			GenericType.BaseType type;
			
			if(returnType != null){
				var popped = stack.pop();
				if(!popped.instanceOf(owner.typeSource, returnType)) throw new MalformedJorth("Method returns " + returnType + " but " + popped + " is on stack");
				type = popped.getBaseType();
			}else{
				if(!stack.isEmpty()){
					throw new MalformedJorth("Returning nothing (void) but there are values " + stack + " on the stack");
				}
				type = GenericType.BaseType.VOID;
			}
			
			writer.visitInsn(type.returnOp());
		}
		
		public void swap() throws MalformedJorth{
			stack.requireElements(2);
			var top      = stack.pop();
			var belowTop = stack.pop();
			
			stack.push(top);
			stack.push(belowTop);
			
			switch(top.getBaseType().slots()){
				case 1 -> {
					switch(belowTop.getBaseType().slots()){
						case 1 -> writer.visitInsn(SWAP);
						case 2 -> {
							writer.visitInsn(DUP_X2);
							writer.visitInsn(POP);
						}
						default -> throw new NotImplementedException(belowTop.toString());
					}
				}
				case 2 -> {
					switch(belowTop.getBaseType().slots()){
						case 1 -> {
							writer.visitInsn(DUP2_X1);
							writer.visitInsn(POP2);
						}
						case 2 -> {
							writer.visitInsn(DUP2_X2);
							writer.visitInsn(POP2);
						}
						default -> throw new NotImplementedException(belowTop.toString());
					}
				}
				default -> throw new NotImplementedException(top.toString());
			}
		}
		public void dup() throws MalformedJorth{
			var type = stack.pop();
			stack.push(type);
			stack.push(type);
			var bt = type.getBaseType();
			writer.visitInsn(switch(bt.slots()){
				case 1 -> DUP;
				case 2 -> DUP2;
				default -> throw new MalformedJorth("Illegal base type: " + bt);
			});
		}
		public void pop() throws MalformedJorth{
			var bt = stack.pop().getBaseType();
			writer.visitInsn(switch(bt.slots()){
				case 1 -> POP;
				case 2 -> POP2;
				default -> throw new MalformedJorth("Illegal base type: " + bt);
			});
		}
	}
	
	private final ClassGen        owner;
	private final String          name;
	private final EnumSet<Access> access;
	private final Visibility      visibility;
	
	private final MethodVisitor writer;
	private final TypeSource    typeSource;
	
	private final GenericType                returnType;
	private final LinkedHashMap<String, Arg> args;
	
	private final ArrayDeque<CodePath> codeInfo = new ArrayDeque<>();
	
	public FunctionGen(ClassGen owner, String name, Visibility visibility, Set<Access> access, GenericType returnType, Collection<ArgInfo> args) throws MalformedJorth{
		this.owner = owner;
		this.name = name;
		this.access = access.isEmpty()? EnumSet.noneOf(Access.class) : EnumSet.copyOf(access);
		this.returnType = returnType;
		this.typeSource = owner.typeSource;
		this.visibility = visibility;
		
		if(returnType != null) typeSource.byType(returnType);
		for(ArgInfo value : args){
			typeSource.byType(value.type);
		}
		
		this.args = LinkedHashMap.newLinkedHashMap(args.size());
		
		int counter = isStatic()? 0 : 1;
		for(ArgInfo value : args){
			var info = value.type.getBaseType();
			this.args.put(value.name, new Arg(value.type, info, value.name, counter));
			counter += info.slots();
		}
		
		if(owner.type == ClassType.INTERFACE){
			this.access.add(Access.ABSTRACT);
		}
		if(name.equals("<clinit>")){
			this.access.add(Access.STATIC);
		}
		
		var accessFlags = visibility.flag;
		for(var acc : this.access){
			accessFlags |= acc.flag;
		}
		
		var argTypes = new ArrayList<GenericType>(args.size());
		for(ArgInfo value : args){
			argTypes.add(value.type);
		}
		
		var descriptor = makeFunSig(returnType, argTypes, false);
		var signature  = makeFunSig(returnType, argTypes, true);
		
		if(descriptor.equals(signature)) signature = null;
		
		writer = owner.writer.visitMethod(accessFlags, name, descriptor, signature, null);
		
		codeInfo.add(new CodePath(writer, new TypeStack(null)));
	}
	
	@Override
	public void end() throws MalformedJorth{
		if(codeInfo.size() != 1){
			throw new NotImplementedException();
		}
		
		var c = code();
		if(!c.ended){
			c.end();
		}
		writer.visitMaxs(0, 0);
		writer.visitEnd();
	}
	
	public void getOp(String owner, String member) throws MalformedJorth{
		ClassInfo info;
		var       code = code();
		switch(owner){
			case "this" -> {
				info = this.owner;
				loadThisIns();
				
				if(member.equals("this")){
					return;
				}
			}
			case "#arg" -> {
				var arg = args.get(member);
				if(arg != null){
					code.loadArgumentIns(arg);
					return;
				}else{
					throw new MalformedJorth("Argument " + member + " does not exist");
				}
			}
			default -> {
				info = typeSource.byType(new GenericType(ClassName.dotted(owner)));
			}
		}
		
		var memberInfo = info.getField(member);
		code.getFieldIns(memberInfo);
	}
	public void setOp(String owner, String member) throws MalformedJorth{
		ClassInfo info;
		switch(owner){
			case "this" -> {
				info = this.owner;
				loadThisIns();
				code().swap();
			}
			case "#arg" -> throw new MalformedJorth("Can not set args");
			default -> info = typeSource.byType(new GenericType(ClassName.dotted(owner)));
		}
		
		var memberInfo = info.getField(member);
		code().setFieldIns(memberInfo);
	}
	
	private static String makeFunSig(GenericType returnType, Collection<GenericType> args, boolean signature){
		
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
	
	private CodePath code(){
		assert codeInfo.peekLast() != null;
		return codeInfo.peekLast();
	}
	public TypeStack getStack(){
		return code().stack;
	}
	
	public void superOp() throws MalformedJorth{
		if(!name.equals("<init>")){
			throw new NotImplementedException("Super on non constructor not implemented");
		}
		loadThisIns();
		var sup = owner.superType();
		invokeOp(sup.getFunction(new Signature("<init>")), true);
	}
	
	public void newOp(GenericType type) throws MalformedJorth{
		var stack = code().stack;
		if(type.dims()>0){
			var arraySize = stack.pop();
			if(!List.of(int.class, short.class, byte.class).contains(arraySize.getBaseType().type())){
				throw new MalformedJorth("Array size is not an integer");
			}
			if(type.dims()>1) throw new NotImplementedException("Multi array not implemented");//TODO
			
			writer.visitTypeInsn(type.getPrimitiveType().isPresent()? NEWARRAY : ANEWARRAY, type.raw().slashed());
		}else{
			writer.visitTypeInsn(NEW, type.raw().slashed());
		}
		
		stack.push(type);
	}
	
	public void loadThisIns(){
		code().loadThisIns();
	}
	
	public void invokeOp(FunctionInfo function, boolean superCall) throws MalformedJorth{
		var owner = function.owner();
		var name  = function.name();
		
		int callOp;
		if(owner.type() == ClassType.INTERFACE){
			callOp = INVOKEINTERFACE;
		}else if(function.isStatic()){
			callOp = INVOKESTATIC;
		}else{
			//https://stackoverflow.com/a/13764338
			if(superCall ||
			   name.equals("<init>") ||
			   function.visibility() == Visibility.PRIVATE ||
			   function.isFinal()
			){
				callOp = INVOKESPECIAL;
			}else{
				callOp = INVOKEVIRTUAL;
			}
		}
		
		var ownerName = owner.name().slashed();
		var ownerType = owner.type();
		
		GenericType       returnType = function.returnType();
		List<GenericType> argTypes   = function.argumentTypes();
		
		var stack = code().stack;
		
		for(int i = argTypes.size() - 1; i>=0; i--){
			var popped = stack.pop();
			var arg    = argTypes.get(i);
			if(popped.instanceOf(typeSource, arg)) continue;
			
			throw new MalformedJorth("Argument " + i + " in " + owner.name() + "#" + name + " is " + arg + " but got " + popped);
		}
		if(!function.isStatic()){
			var popped = stack.pop();
			var arg    = new GenericType(owner.name());
			
			if(!popped.instanceOf(typeSource, arg)){
				throw new MalformedJorth("Function caller is " + owner.name() + " but callee on stack is " + popped);
			}
		}
		
		if(returnType != null){
			code().stack.push(returnType);
		}
		
		var signature = makeFunSig(returnType, argTypes, false);
		
		writer.visitMethodInsn(callOp, ownerName, name, signature, ownerType == ClassType.INTERFACE);
	}
	
	public Endable startCall(ClassName staticOwner, String functionName) throws MalformedJorth{
		var mark = code().stack.size();
		
		ClassInfo cInfo;
		if(staticOwner != null){
			cInfo = typeSource.byName(staticOwner);
		}else{
			var stack = code().stack;
			if(stack.isEmpty()) throw new MalformedJorth("Trying to call non static method but there is nothing on stack");
			cInfo = typeSource.byType(stack.peek(mark - 1));
		}
		
		return () -> {
			var stack    = code().stack;
			var argCount = stack.size() - mark;
			if(argCount<0) throw new MalformedJorth("Negative stack delta inside arg block");
			var args = new ArrayList<GenericType>(argCount);
			for(int i = 0; i<argCount; i++){
				args.add(stack.peek(mark + i));
			}
			
			var info = cInfo.getFunction(new Signature(functionName, args));
			
			invokeOp(info, false);
		};
	}
	
	public void compareEqualOp(boolean checkFor) throws MalformedJorth{
		var stack = code().stack;
		stack.requireElements(2);
		
		var a = stack.pop();
		var b = stack.pop();
		
		if(!a.instanceOf(typeSource, b) && !b.instanceOf(typeSource, a)){
			throw new MalformedJorth(a + " not compatible with " + b);
		}
		
		stack.push(GenericType.BOOL);
		
		var prim = a.getPrimitiveType();
		if(prim.isPresent()){
			if(prim.get().loadOp() == ILOAD){
				branchCompareToBool(checkFor? IF_ICMPNE : IF_ICMPEQ);
			}else{
				throw new NotImplementedException();
			}
		}else{
			branchCompareToBool(checkFor? IF_ACMPNE : IF_ACMPEQ);
		}
	}
	
	public void pushIfBool() throws MalformedJorth{
		var stack = code().stack;
		var typ   = stack.pop();
		if(!typ.equals(GenericType.BOOL)){
			throw new MalformedJorth("Got " + typ + " but need a boolean for if statement");
		}
		
		var newPath = new CodePath(writer, stack);
		codeInfo.add(newPath);
		
		//Jump if false
		writer.visitJumpInsn(IFEQ, newPath.endLabel);
	}
	
	public void popIf() throws MalformedJorth{
		var ifPath = codeInfo.removeLast();
		if(!ifPath.stack.isEmpty()){
			throw new MalformedJorth("If ended but there is data left in if code block");
		}
		writer.visitLabel(ifPath.endLabel);
	}
	
	public void returnOp() throws MalformedJorth{
		code().end();
	}
	
	public void throwOp() throws MalformedJorth{
		var stack = code().stack;
		var typ   = stack.pop();
		if(!typ.instanceOf(typeSource, new GenericType(ClassName.of(Throwable.class)))){
			throw new MalformedJorth("Got " + typ + " but need a boolean for if statement");
		}
		
		writer.visitInsn(ATHROW);
		code().ended = true;
	}
	
	private void branchCompareToBool(int ifNotOp){
		Label falseL = new Label();
		writer.visitJumpInsn(ifNotOp, falseL);
		writer.visitInsn(ICONST_1);
		Label endL = new Label();
		writer.visitJumpInsn(GOTO, endL);
		writer.visitLabel(falseL);
		writer.visitInsn(ICONST_0);
		writer.visitLabel(endL);
	}
	
	
	public void loadStringOp(String str){
		writer.visitLdcInsn(str);
		code().stack.push(GenericType.STRING);
	}
	
	public void loadIntOp(int value){
		code().stack.push(GenericType.INT);
		switch(value){
			case 0 -> writer.visitInsn(ICONST_0);
			case 1 -> writer.visitInsn(ICONST_1);
			case 2 -> writer.visitInsn(ICONST_2);
			case 3 -> writer.visitInsn(ICONST_3);
			case 4 -> writer.visitInsn(ICONST_4);
			case 5 -> writer.visitInsn(ICONST_5);
			default -> writer.visitIntInsn(SIPUSH, value);
		}
	}
	
	public void loadBooleanOp(boolean value){
		code().stack.push(GenericType.BOOL);
		if(value){
			writer.visitInsn(ICONST_1);
		}else{
			writer.visitInsn(ICONST_0);
		}
	}
	
	public void loadFloatOp(float value){
		code().stack.push(GenericType.BOOL);
		writer.visitLdcInsn(value);
	}
	
	@Override
	public boolean isStatic(){
		return access.contains(Access.STATIC);
	}
	@Override
	public boolean isFinal(){
		return access.contains(Access.FINAL);
	}
	@Override
	public Visibility visibility(){
		return visibility;
	}
	@Override
	public ClassInfo owner(){
		return owner;
	}
	@Override
	public String name(){
		return name;
	}
	@Override
	public GenericType returnType(){
		return returnType;
	}
	@Override
	public List<GenericType> argumentTypes(){
		return args.values().stream().map(a -> a.type).toList();
	}
	
	@Override
	public String toString(){
		return name + "()";
	}
	
	public void dupOp() throws MalformedJorth{
		code().dup();
	}
	public void popOp() throws MalformedJorth{
		code().pop();
	}
}
