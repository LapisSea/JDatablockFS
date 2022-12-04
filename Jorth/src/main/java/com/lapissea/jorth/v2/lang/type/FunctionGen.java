package com.lapissea.jorth.v2.lang.type;

import com.lapissea.jorth.MalformedJorthException;
import com.lapissea.jorth.v2.lang.ClassName;
import com.lapissea.jorth.v2.lang.Endable;
import com.lapissea.jorth.v2.lang.info.FunctionInfo;
import com.lapissea.util.NotImplementedException;
import org.objectweb.asm.MethodVisitor;

import java.util.*;

import static org.objectweb.asm.Opcodes.*;

public final class FunctionGen implements Endable, FunctionInfo{
	
	public record ArgInfo(GenericType type, String name, boolean isStatic){ }
	
	private record Arg(GenericType type, GenericType.BaseType info, String name, boolean isStatic, int accessIndex){ }
	
	private class CodePath{
		private final TypeStack stack;
		
		private boolean ended;
		
		public CodePath(MethodVisitor mv, TypeStack stack){
			this.stack = stack;
		}
		
		public void loadThisIns(){
			writer.visitVarInsn(ALOAD, 0);
			stack.push(new GenericType(owner.name));
		}
		
		public void getFieldIns(FieldInfo field) throws MalformedJorthException{
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
		
		public void setFieldIns(FieldInfo field) throws MalformedJorthException{
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
		
		public void end() throws MalformedJorthException{
			ended = true;
			
			GenericType.BaseType type;
			
			if(returnType != null){
				var popped = stack.pop();
				if(!popped.instanceOf(owner.typeSource, returnType)) throw new MalformedJorthException("Method returns " + returnType + " but " + popped + " is on stack");
				type = popped.getBaseType();
			}else{
				if(!stack.isEmpty()){
					throw new MalformedJorthException("Returning nothing (void) but there are values " + stack + " on the stack");
				}
				type = GenericType.BaseType.VOID;
			}
			
			writer.visitInsn(type.returnOp());
		}
		
		public void swap() throws MalformedJorthException{
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
		public void dup() throws MalformedJorthException{
			var type = stack.pop();
			stack.push(type);
			stack.push(type);
			writer.visitInsn(DUP);
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
	
	public FunctionGen(ClassGen owner, String name, Visibility visibility, Set<Access> access, GenericType returnType, LinkedHashMap<String, ArgInfo> args){
		this.owner = owner;
		this.name = name;
		this.access = access.isEmpty()? EnumSet.noneOf(Access.class) : EnumSet.copyOf(access);
		this.returnType = returnType;
		this.typeSource = owner.typeSource;
		this.visibility = visibility;
		
		this.args = LinkedHashMap.newLinkedHashMap(args.size());
		
		int instanceCounter = 1;
		int staticCounter   = 0;
		for(ArgInfo value : args.values()){
			var info = value.type.getBaseType();
			int counter;
			if(value.isStatic){
				counter = staticCounter;
				staticCounter += info.slots();
			}else{
				counter = instanceCounter;
				instanceCounter += info.slots();
			}
			this.args.put(value.name, new Arg(value.type, info, value.name, value.isStatic, counter));
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
		for(ArgInfo value : args.values()){
			argTypes.add(value.type);
		}
		
		var descriptor = makeFunSig(returnType, argTypes, false);
		var signature  = makeFunSig(returnType, argTypes, true);
		
		if(descriptor.equals(signature)) signature = null;
		
		writer = owner.writer.visitMethod(accessFlags, name, descriptor, signature, null);
		
		codeInfo.add(new CodePath(writer, new TypeStack(null)));
	}
	
	@Override
	public void end() throws MalformedJorthException{
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
	
	public void getOp(String owner, String member) throws MalformedJorthException{
		ClassInfo info;
		var       code = code();
		switch(owner){
			case "this" -> {
				info = this.owner;
				loadThisIns();
			}
			case "#arg" -> {
				var arg = args.get(name);
				if(arg != null){
					info = typeSource.byType(arg.type);
					code.loadArgumentIns(arg);
				}else{
					throw new MalformedJorthException("Argument " + name + " does not exist");
				}
			}
			default -> {
				info = typeSource.byType(new GenericType(ClassName.dotted(owner)));
			}
		}
		
		if(member.equals("this")){
			return;
		}
		
		var memberInfo = info.getField(member);
		code.getFieldIns(memberInfo);
	}
	public void setOp(String owner, String member) throws MalformedJorthException{
		ClassInfo info;
		switch(owner){
			case "this" -> {
				info = this.owner;
				loadThisIns();
				code().swap();
			}
			case "#arg" -> throw new MalformedJorthException("Can not set args");
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
	
	public void superOp() throws MalformedJorthException{
		if(!name.equals("<init>")){
			throw new NotImplementedException("Super on non constructor not implemented");
		}
		loadThisIns();
		var sup = owner.superType();
		invokeOp(sup.getFunction(new Signature("<init>")), true);
	}
	
	public void newOp(ClassName clazz, List<GenericType> args) throws MalformedJorthException{
		writer.visitTypeInsn(NEW, clazz.slashed());
		code().stack.push(new GenericType(clazz));
		code().dup();
		invokeOp(typeSource.byName(clazz).getFunction(new Signature("<init>", args)), false);
	}
	
	private void loadThisIns(){
		code().loadThisIns();
	}
	
	public void invokeOp(FunctionInfo function, boolean superCall) throws MalformedJorthException{
		var owner = function.owner();
		var name  = function.name();
		
		int callOp;
		if(function.isStatic()){
			assert !superCall;
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
			
			throw new MalformedJorthException("Argument " + i + " in " + owner.name() + "#" + name + " is " + arg + " but got " + popped);
		}
		if(!function.isStatic()){
			var popped = stack.pop();
			var arg    = new GenericType(owner.name());
			
			if(!popped.instanceOf(typeSource, arg)){
				throw new MalformedJorthException("Function caller is " + owner.name() + " but callee on stack is " + popped);
			}
		}
		
		if(returnType != null){
			code().stack.push(returnType);
		}
		
		var signature = makeFunSig(returnType, argTypes, false);
		
		writer.visitMethodInsn(callOp, ownerName, name, signature, ownerType == ClassType.INTERFACE);
	}
	
	public Endable startCall(ClassName staticOwner, String functionName){
		var mark = code().stack.size();
		
		return () -> {
			var stack    = code().stack;
			var argCount = stack.size() - mark;
			if(argCount<0) throw new MalformedJorthException("Negative stack delta inside arg block");
			var args = new ArrayList<GenericType>(argCount);
			for(int i = 0; i<argCount; i++){
				args.add(stack.peek(mark + i));
			}
			
			ClassInfo cInfo;
			if(staticOwner != null){
				cInfo = typeSource.byName(staticOwner);
			}else{
				cInfo = typeSource.byType(stack.peek(mark - 1));
			}
			
			var info = cInfo.getFunction(new Signature(functionName, args));
			
			invokeOp(info, false);
		};
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
}
