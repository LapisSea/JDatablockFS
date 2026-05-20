package com.lapissea.jorth.redo;

import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import com.lapissea.jorth.lang.info.FunctionInfo;
import com.lapissea.jorth.lang.type.BaseType;
import com.lapissea.jorth.lang.type.ClassInfo;
import com.lapissea.jorth.lang.type.ClassType;
import com.lapissea.jorth.lang.type.GenericType;
import com.lapissea.jorth.lang.type.JType;
import com.lapissea.jorth.lang.type.TypeSource;
import com.lapissea.jorth.lang.type.TypeStack;
import com.lapissea.jorth.lang.type.Visibility;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.ShouldNeverHappenError;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import java.util.Collection;

import static org.objectweb.asm.Opcodes.*;

public sealed interface Insn{
	
	record GetLocal(GenericType type, String name, int index) implements Insn{
		public static GetLocal simulate(TypeStack stack, GenericType type, String name, int index){
			stack.push(type);
			return new GetLocal(type, name, index);
		}
		@Override
		public void visit(MethodVisitor writer){
			writer.visitVarInsn(type.getBaseType().loadOp, index);
		}
	}
	
	record Equality(GenericType type, boolean checkFor) implements Insn{
		
		public static Equality simulate(TypeSource typeSource, TypeStack stack, boolean checkFor) throws MalformedJorth{
			stack.requireElements(2);
			GenericType a = stack.pop();
			GenericType b = stack.pop();
			
			if(!a.instanceOf(typeSource, b) && !b.instanceOf(typeSource, a)){
				throw new MalformedJorth(a + " not compatible with " + b);
			}
			
			stack.push(GenericType.BOOL);
			return new Equality(a, checkFor);
		}
		@Override
		public void visit(MethodVisitor writer){
			BaseType prim = type.getPrimitiveType().orElse(BaseType.OBJ);
			switch(prim.loadOp){
				case ALOAD -> {
					branchCompareToBool(writer, checkFor? IF_ACMPNE : IF_ACMPEQ);
				}
				case ILOAD -> {
					branchCompareToBool(writer, checkFor? IF_ICMPNE : IF_ICMPEQ);
				}
				case LLOAD -> {
					writer.visitInsn(LCMP);
					branchCompareToBool(writer, checkFor? IFNE : IFEQ);
				}
				case FLOAD -> {
					writer.visitInsn(FCMPL);
					branchCompareToBool(writer, checkFor? IFNE : IFEQ);
				}
				case DLOAD -> {
					writer.visitInsn(DCMPL);
					branchCompareToBool(writer, checkFor? IFNE : IFEQ);
				}
				default -> {
					throw new NotImplementedException("Unsupported primitive compare type: " + prim);
				}
			}
		}
		private void branchCompareToBool(MethodVisitor writer, int ifNotOp){
			Label falseL = new Label();
			writer.visitJumpInsn(ifNotOp, falseL);
			writer.visitInsn(ICONST_1);
			Label endL = new Label();
			writer.visitJumpInsn(GOTO, endL);
			writer.visitLabel(falseL);
			writer.visitInsn(ICONST_0);
			writer.visitLabel(endL);
		}
	}
	
	record ReturnOp(BaseType typ) implements Insn{
		
		static ReturnOp simulate(JType returnType, TypeSource typeSource, TypeStack stack, boolean modifyStack) throws MalformedJorth{
			if(returnType != null){
				var ret    = returnType.asGeneric();
				var popped = modifyStack? stack.pop() : stack.peekLast();
				if(!popped.instanceOf(typeSource, ret)){
					throw new MalformedJorth("Method returns " + ret + " but " + popped + " is on stack");
				}
				return new ReturnOp(popped.getBaseType());
			}else{
				if(!stack.isEmpty()){
					throw new MalformedJorth("Returning nothing (void) but there are values " + stack + " on the stack");
				}
				return new ReturnOp(BaseType.VOID);
			}
		}
		
		@Override
		public void visit(MethodVisitor writer){
			writer.visitInsn(typ.returnOp);
		}
	}
	
	record PopOp(int slots) implements Insn{
		
		static PopOp simulate(TypeStack localStack) throws MalformedJorth{
			var bt = localStack.pop().getBaseType();
			return new PopOp(bt.slots);
		}
		
		@Override
		public void visit(MethodVisitor writer){
			writer.visitInsn(switch(slots){
				case 1 -> POP;
				case 2 -> POP2;
				default -> throw new ShouldNeverHappenError();
			});
		}
	}
	
	record InvokeOp(FunctionInfo function, boolean superCall, ClassName caller) implements Insn{
		
		static InvokeOp simulate(TypeStack stack, TypeSource typeSource, ClassName caller, FunctionInfo function, boolean superCall) throws MalformedJorth{
			ClassInfo owner = function.owner();
			String    name  = function.name();
			
			var returnType = function.returnType();
			var argTypes   = function.argumentTypes();
			
			for(int i = argTypes.size() - 1; i>=0; i--){
				var popped = stack.pop();
				var arg    = argTypes.get(i).asGeneric();
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
				stack.push(returnType.asGeneric());
			}
			
			return new InvokeOp(function, superCall, caller);
		}
		
		@Override
		public void visit(MethodVisitor writer){
			ClassInfo owner = function.owner();
			String    name  = function.name();
			
			
			var ownerName = owner.name().slashed();
			var ownerType = owner.type();
			
			int callOp;
			if(function.isStatic()){
				callOp = INVOKESTATIC;
			}else if(owner.type() == ClassType.INTERFACE){
				callOp = INVOKEINTERFACE;
			}else{
				//https://stackoverflow.com/a/13764338
				if(superCall ||
				   name.equals("<init>") ||
				   (function.visibility() == Visibility.PRIVATE && owner.name().equals(caller))
				){
					callOp = INVOKESPECIAL;
				}else{
					callOp = INVOKEVIRTUAL;
				}
			}
			
			var signature = makeFunSig(function.returnType(), function.argumentTypes(), false);
			
			writer.visitMethodInsn(callOp, ownerName, name, signature, ownerType == ClassType.INTERFACE);
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
	
	void visit(MethodVisitor writer);
}
