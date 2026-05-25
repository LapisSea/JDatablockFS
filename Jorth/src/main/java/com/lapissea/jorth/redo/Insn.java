package com.lapissea.jorth.redo;

import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import com.lapissea.jorth.lang.info.FunctionInfo;
import com.lapissea.jorth.lang.type.BaseType;
import com.lapissea.jorth.lang.type.ClassInfo;
import com.lapissea.jorth.lang.type.ClassType;
import com.lapissea.jorth.lang.type.FieldInfo;
import com.lapissea.jorth.lang.type.GenericType;
import com.lapissea.jorth.lang.type.JType;
import com.lapissea.jorth.lang.type.TypeSource;
import com.lapissea.jorth.lang.type.TypeStack;
import com.lapissea.jorth.lang.type.Visibility;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.UtilL;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.objectweb.asm.Opcodes.*;

public sealed interface Insn{
	
	private static GenericType doEquality(TypeSource typeSource, TypeStack stack) throws MalformedJorth{
		stack.requireElements(2);
		GenericType a = stack.pop();
		GenericType b = stack.pop();
		
		if(!a.instanceOf(typeSource, b) && !b.instanceOf(typeSource, a)){
			throw new MalformedJorth(a + " not compatible with " + b);
		}
		return a;
	}
	
	record IVal(int val) implements Insn{
		public static void emit(MethodVisitor writer, int value){
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
		
		public static IVal simulate(TypeStack stack, int val){
			stack.push(GenericType.INT);
			return new IVal(val);
		}
		@Override
		public void visit(MethodVisitor writer){
			emit(writer, val);
		}
	}
	
	record LVal(long val) implements Insn{
		public static void emit(MethodVisitor writer, long value){
			switch((int)value){
				case 0 -> writer.visitInsn(LCONST_0);
				case 1 -> writer.visitInsn(LCONST_1);
				default -> writer.visitLdcInsn(value);
			}
		}
		
		public static LVal simulate(TypeStack stack, long val){
			stack.push(GenericType.LONG);
			return new LVal(val);
		}
		@Override
		public void visit(MethodVisitor writer){
			emit(writer, val);
		}
	}
	
	record FVal(float val) implements Insn{
		public static void emit(MethodVisitor writer, float value){
			if(value == 0.0f) writer.visitInsn(FCONST_0);
			else if(value == 1.0f) writer.visitInsn(FCONST_1);
			else if(value == 2.0f) writer.visitInsn(FCONST_2);
			else writer.visitLdcInsn(value);
		}
		
		public static FVal simulate(TypeStack stack, float val){
			stack.push(GenericType.FLOAT);
			return new FVal(val);
		}
		@Override
		public void visit(MethodVisitor writer){
			emit(writer, val);
		}
	}
	
	record DVal(double val) implements Insn{
		public static void emit(MethodVisitor writer, double value){
			if(value == 0.0d) writer.visitInsn(DCONST_0);
			else if(value == 1.0d) writer.visitInsn(DCONST_1);
			else writer.visitLdcInsn(value);
		}
		public static DVal simulate(TypeStack stack, double val){
			stack.push(GenericType.DOUBLE);
			return new DVal(val);
		}
		@Override
		public void visit(MethodVisitor writer){
			emit(writer, val);
		}
	}
	
	record BVal(boolean val) implements Insn{
		public static void emit(MethodVisitor writer, boolean value){
			if(value){
				writer.visitInsn(ICONST_1);
			}else{
				writer.visitInsn(ICONST_0);
			}
		}
		public static BVal simulate(TypeStack stack, boolean val){
			stack.push(GenericType.BOOL);
			return new BVal(val);
		}
		@Override
		public void visit(MethodVisitor writer){
			emit(writer, val);
		}
	}
	
	record StrVal(String val) implements Insn{
		public StrVal{
			Objects.requireNonNull(val);
		}
		public static StrVal simulate(TypeStack stack, String val){
			stack.push(GenericType.STRING);
			return new StrVal(val);
		}
		@Override
		public void visit(MethodVisitor writer){
			writer.visitLdcInsn(val);
		}
	}
	
	record ClassVal(GenericType type) implements Insn{
		public static ClassVal simulate(TypeStack stack, ClassName clazz){
			var type = new GenericType(clazz);
			stack.push(new GenericType(ClassName.of(Class.class), Optional.empty(), 0, List.of(type)));
			return new ClassVal(type);
		}
		@Override
		public void visit(MethodVisitor writer){
			writer.visitLdcInsn(org.objectweb.asm.Type.getType(type.jvmSignatureStr()));
		}
	}
	
	record PrimitiveCastOp(GenericType from, GenericType to) implements Insn{
		
		@Override
		public void visit(MethodVisitor writer){
			if(from.equals(GenericType.LONG) && to.equals(GenericType.INT)){
				writer.visitInsn(L2I);
			}else if(from.equals(GenericType.INT) && to.equals(GenericType.LONG)){
				writer.visitInsn(I2L);
			}else if(from.equals(GenericType.INT) && to.equals(GenericType.BYTE)){
				writer.visitInsn(I2B);
			}else if(from.equals(GenericType.INT) && to.equals(GenericType.CHAR)){
				writer.visitInsn(I2C);
			}else if(from.equals(GenericType.INT) && to.equals(GenericType.SHORT)){
				writer.visitInsn(I2S);
			}else if(from.equals(GenericType.INT) && to.equals(GenericType.BOOL)){
				emitIntToTruthy(writer);
			}else if(from.equals(GenericType.LONG) && to.equals(GenericType.BOOL)){
				writer.visitInsn(LCONST_0);
				writer.visitInsn(LCMP);
				emitIntToTruthy(writer);
			}else if(from.equals(GenericType.LONG) && to.equals(GenericType.FLOAT)){
				writer.visitInsn(L2F);
			}else if(from.equals(GenericType.LONG) && to.equals(GenericType.DOUBLE)){
				writer.visitInsn(L2D);
			}else if(from.equals(GenericType.FLOAT) && to.equals(GenericType.INT)){
				writer.visitInsn(F2I);
			}else if(from.equals(GenericType.FLOAT) && to.equals(GenericType.LONG)){
				writer.visitInsn(F2L);
			}else if(from.equals(GenericType.FLOAT) && to.equals(GenericType.DOUBLE)){
				writer.visitInsn(F2D);
			}else if(from.equals(GenericType.DOUBLE) && to.equals(GenericType.INT)){
				writer.visitInsn(D2I);
			}else if(from.equals(GenericType.DOUBLE) && to.equals(GenericType.LONG)){
				writer.visitInsn(D2L);
			}else if(from.equals(GenericType.DOUBLE) && to.equals(GenericType.FLOAT)){
				writer.visitInsn(D2F);
			}else{
				throw new UnsupportedOperationException("Unsupported primitive cast: " + from + " -> " + to);
			}
		}
		
		private void emitIntToTruthy(MethodVisitor writer){
			Label isTrue = new Label();
			Label end    = new Label();
			
			// if int != 0, jump to true
			writer.visitJumpInsn(IFNE, isTrue);
			writer.visitInsn(ICONST_0);
			writer.visitJumpInsn(GOTO, end);
			
			writer.visitLabel(isTrue);
			writer.visitInsn(ICONST_1);
			writer.visitLabel(end);
		}
	}
	
	record CastOp(GenericType type) implements Insn{
		public static Insn simulate(TypeStack stack, TypeSource typeSource, GenericType type) throws MalformedJorth{
			var stackType = stack.pop();
			
			if(type.equals(stackType)){
				stack.push(type);
				return null;
			}
			
			if(stackType.getBaseType() != BaseType.OBJ && type.getBaseType() != BaseType.OBJ){
				stack.push(type);
				return new PrimitiveCastOp(stackType, type);
			}
			
			if(!type.instanceOf(typeSource, stackType)){
				throw new MalformedJorth("Can not cast " + stackType + " to " + type);
			}
			stack.push(type);
			return new CastOp(type);
		}
		@Override
		public void visit(MethodVisitor writer){
			writer.visitTypeInsn(CHECKCAST, type.dims() == 0? type.raw().slashed() : type.jvmDescriptorStr());
		}
	}
	
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
			GenericType a = doEquality(typeSource, stack);
			
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
		
		static PopOp simulate(TypeStack stack) throws MalformedJorth{
			var bt = stack.pop().getBaseType();
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
	
	record DupOp(int slots) implements Insn{
		
		static DupOp simulate(TypeStack stack) throws MalformedJorth{
			var e = stack.pop();
			stack.push(e);
			stack.push(e);
			return new DupOp(e.getBaseType().slots);
		}
		
		@Override
		public void visit(MethodVisitor writer){
			writer.visitInsn(switch(slots){
				case 1 -> DUP;
				case 2 -> DUP2;
				default -> throw new ShouldNeverHappenError();
			});
		}
	}
	
	record SwapOp(GenericType top, GenericType belowTop) implements Insn{
		
		static SwapOp simulate(TypeStack stack) throws MalformedJorth{
			stack.requireElements(2);
			var top      = stack.pop();
			var belowTop = stack.pop();
			
			stack.push(top);
			stack.push(belowTop);
			return new SwapOp(top, belowTop);
		}
		
		@Override
		public void visit(MethodVisitor writer){
			switch(top.getBaseType().slots){
				case 1 -> {
					switch(belowTop.getBaseType().slots){
						case 1 -> writer.visitInsn(SWAP);
						case 2 -> {
							writer.visitInsn(DUP_X2);
							writer.visitInsn(POP);
						}
						default -> throw new NotImplementedException(belowTop.toString());
					}
				}
				case 2 -> {
					switch(belowTop.getBaseType().slots){
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
	}
	
	record ConditionalJump(Type type, GenericType vType, CodeBlock onTrue, CodeBlock onFalse) implements Insn{
		
		private static void checkBranches(TypeStack stack, CodeBlock onTrue, CodeBlock onFalse) throws MalformedJorth{
			CodeBlock nonTermTrue  = onTrue == null || onTrue.terminates()? null : onTrue;
			CodeBlock nonTermFalse = onFalse == null || onFalse.terminates()? null : onFalse;
			
			if(nonTermTrue != null && nonTermFalse != null){
				if(!nonTermTrue.stacksMatch(nonTermFalse)){
					throw new MalformedJorth("True and false branches of conditional jump must have the same stack");
				}
			}
			
			if(nonTermTrue == null || nonTermFalse == null){
				var other = nonTermTrue == null? nonTermFalse : nonTermTrue;
				if(other != null && !other.stacksMatch(stack)){
					throw new MalformedJorth("Conditional jump must have the same stack as the base." + other);
				}
			}
		}
		
		enum Type{
			TRUE_BOOL,
			EQUALITY
		}
		
		static ConditionalJump simulate(TypeStack stack, TypeSource typeSource, Type type, CodeBlock onTrue, CodeBlock onFalse) throws MalformedJorth{
			var vType = switch(type){
				case TRUE_BOOL -> {
					var typ = stack.pop();
					if(!typ.getBaseType().type.equals(boolean.class)){
						throw new MalformedJorth("Condition must be boolean");
					}
					yield null;
				}
				case EQUALITY -> doEquality(typeSource, stack);
			};
			checkBranches(stack, onTrue, onFalse);
			return new ConditionalJump(type, vType, onTrue, onFalse);
		}
		
		@Override
		public void visit(MethodVisitor writer){
			
			Label endLabel   = new Label();
			Label falseLabel = new Label();
			
			switch(type){
				case TRUE_BOOL -> {
					if(onTrue != null && onFalse != null){
						writer.visitJumpInsn(IFEQ, falseLabel);
						onTrue.visit(writer);
						writer.visitJumpInsn(GOTO, endLabel);
						writer.visitLabel(falseLabel);
						onFalse.visit(writer);
						writer.visitLabel(endLabel);
					}else if(onTrue != null){
						writer.visitJumpInsn(IFEQ, endLabel);
						onTrue.visit(writer);
						writer.visitLabel(endLabel);
					}else{
						writer.visitJumpInsn(IFNE, endLabel);
						onFalse.visit(writer);
						writer.visitLabel(endLabel);
					}
				}
				case EQUALITY -> {
					throw new NotImplementedException();
				}
			}
		}
		public ConditionalJump withFalse(TypeStack stack, CodeBlock onFalse) throws MalformedJorth{
			checkBranches(stack, onTrue, onFalse);
			return new ConditionalJump(type, vType, onTrue, onFalse);
		}
	}
	
	record NewOp(GenericType type, boolean dup) implements Insn{
		
		static NewOp simulate(TypeStack stack, GenericType type, boolean dup) throws MalformedJorth{
			switch(type.dims()){
				case 0 -> { }
				case 1 -> {
					var arraySize = stack.pop();
					if(!List.of(int.class, short.class, byte.class).contains(arraySize.getBaseType().type)){
						throw new MalformedJorth("Array size is not an integer");
					}
				}
				default -> throw new NotImplementedException("Multi array not implemented");//TODO
			}
			stack.push(type);
			if(dup){
				stack.push(type);
			}
			return new NewOp(type, dup);
		}
		
		@Override
		public void visit(MethodVisitor writer){
			int op = switch(type.dims()){
				case 0 -> NEW;
				case 1 -> type.getPrimitiveType().isPresent()? NEWARRAY : ANEWARRAY;
				default -> throw new NotImplementedException("Multi array not implemented");//TODO
			};
			writer.visitTypeInsn(op, type.raw().slashed());
			if(dup){
				writer.visitInsn(DUP);
			}
		}
	}
	
	private static void popFieldOwner(TypeStack stack, TypeSource typeSource, FieldInfo field, ClassName owner) throws MalformedJorth{
		if(field.isStatic()) return;
		var stackOwnerType = stack.pop().raw();
		if(!stackOwnerType.instanceOf(typeSource, owner)){
			throw new ClassCastException(stackOwnerType + " not compatible with " + owner);
		}
	}
	private static void fieldAccess(MethodVisitor writer, FieldInfo field, int accOp){
		var type = field.type().asGeneric().withoutArgs();
		writer.visitFieldInsn(accOp, field.owner().slashed(), field.name(), type.jvmDescriptorStr());
	}
	
	record PutFieldOp(FieldInfo field) implements Insn{
		
		static PutFieldOp simulate(TypeStack stack, TypeSource typeSource, FieldInfo field) throws MalformedJorth{
			stack.requireElements(field.isStatic()? 1 : 2);
			var valueType = stack.pop().withoutArgs();
			var type      = field.type().asGeneric().withoutArgs();
			
			if(!valueType.instanceOf(typeSource, type)){
				throw new ClassCastException(valueType + " not compatible with " + type);
			}
			
			Insn.popFieldOwner(stack, typeSource, field, field.owner());
			return new PutFieldOp(field);
		}
		
		@Override
		public void visit(MethodVisitor writer){
			fieldAccess(writer, field, field.isStatic()? PUTSTATIC : PUTFIELD);
		}
	}
	
	record GetFieldOp(FieldInfo field) implements Insn{
		
		static GetFieldOp simulate(TypeStack stack, TypeSource typeSource, FieldInfo field) throws MalformedJorth{
			var type = field.type().asGeneric().withoutArgs();
			Insn.popFieldOwner(stack, typeSource, field, field.owner());
			stack.push(type);
			return new GetFieldOp(field);
		}
		
		@Override
		public void visit(MethodVisitor writer){
			fieldAccess(writer, field, field.isStatic()? GETSTATIC : GETFIELD);
		}
	}
	
	
	record PutElementOp(GenericType element) implements Insn{
		
		static PutElementOp simulate(TypeStack stack, TypeSource typeSource) throws MalformedJorth{
			GenericType element = stack.pop();
			GenericType index   = stack.pop();
			GenericType array   = stack.pop();
			
			if(!index.getBaseType().arrayIndexCompatible){
				throw new MalformedJorth(index + " can not be used as array index");
			}
			if(array.dims() == 0){
				throw new MalformedJorth(array + " is not an array");
			}
			if(!element.instanceOf(typeSource, array.withDims(array.dims() - 1))){
				throw new MalformedJorth("can not store " + element + " in " + array);
			}
			return new PutElementOp(element);
		}
		
		@Override
		public void visit(MethodVisitor writer){
			writer.visitInsn(element.getBaseType().arrayStoreOP);
		}
	}
	
	
	record InvokeOp(FunctionInfo function, boolean superCall, ClassName caller) implements Insn{
		
		static InvokeOp simulate(TypeStack stack, TypeSource typeSource, ClassName caller, FunctionInfo function, boolean superCall) throws MalformedJorth{
			ClassInfo owner = function.ownerInfo();
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
			ClassInfo owner = function.ownerInfo();
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
	
	record VirtualCallOp(
		FunctionInfo.Signature callingSignature, GenericType callingReturnType, Handle bootstrapHandle, Object[] bootstrapArgs
	) implements Insn{
		
		static VirtualCallOp simulate(TypeStack stack, TypeSource typeSource, BootstrapFn boot, CallingFn callingFn, List<JType> args) throws MalformedJorth{
			if(args.size() != callingFn.args.size()){
				throw new MalformedJorth(
					"Call args size mismatch:\n\t" +
					"Requested: " + callingFn.args + "\n\t" +
					"But got:   " + args
				);
			}
			for(int i = 0; i<args.size(); i++){
				var argType = args.get(i);
				var defType = callingFn.args.get(i);
				if(!argType.asGeneric().instanceOf(typeSource, defType.asGeneric())){
					throw new MalformedJorth(
						"Argument " + i + " does not satisfy type of argument. Is " + argType.asGeneric() + " but " + defType.asGeneric() + " is required"
					);
				}
			}
			
			for(int i = 0; i<args.size(); i++){
				stack.pop();
			}
			
			if(callingFn.returns != null){
				stack.push(callingFn.returns.asGeneric());
			}
			
			List<JType> bArgs = new ArrayList<>(3 + boot.bootstrapStaticArgs.size());
			bArgs.add(GenericType.of(MethodHandles.Lookup.class));
			bArgs.add(GenericType.of(String.class));
			bArgs.add(GenericType.of(MethodType.class));
			for(var arg : boot.bootstrapStaticArgs){
				bArgs.add(arg.type());
			}
			
			ClassInfo    bootstrapClass = typeSource.byName(boot.owner);
			FunctionInfo bootstrapFn    = pickBootstrapFunction(typeSource, boot, bootstrapClass, bArgs);
			
			Handle bsmh = new Handle(
				H_INVOKESTATIC,
				boot.owner.slashed(),
				boot.functionName,
				InvokeOp.makeFunSig(bootstrapFn.returnType(), bootstrapFn.argumentTypes(), false),
				bootstrapFn.ownerInfo().isInterface()
			);
			
			var staticArgs = boot.bootstrapStaticArgs.stream().map(BootstrapFn.StaticArg::value).toArray();
			
			return new VirtualCallOp(new FunctionInfo.Signature(callingFn.name, callingFn.args), callingFn.returns, bsmh, staticArgs);
		}
		private static FunctionInfo pickBootstrapFunction(TypeSource typeSource, BootstrapFn bootstrap, ClassInfo bc, List<JType> bArgs) throws MalformedJorth{
			var fns = bc.getFunctionsByName(bootstrap.functionName).filter(e -> {
				if(!e.returnType().equals(JType.of(CallSite.class))){
					return false;
				}
				var argTypes = e.argumentTypes();
				if(argTypes.size() != bArgs.size()){
					return false;
				}
				for(int i = 0; i<bArgs.size(); i++){
					var argType = bArgs.get(i).asGeneric();
					var fnType  = argTypes.get(i).asGeneric();
					try{
						if(!argType.instanceOf(typeSource, fnType)){
							return false;
						}
					}catch(MalformedJorth ex){
						throw UtilL.uncheckedThrow(ex);
					}
				}
				return true;
			}).toList();
			
			if(fns.isEmpty()){
				throw new MalformedJorth(
					"No valid boostrap function found for " + bootstrap.owner + "." +
					bootstrap.functionName + bArgs.stream()
					                              .map(Object::toString)
					                              .collect(Collectors.joining(", ", "(", ")"))
				);
			}
			if(fns.size() != 1){
				throw new MalformedJorth("Ambiguous boostrap function found for " + bootstrap.owner + "#" + bootstrap.functionName);
			}
			return fns.getFirst();
		}
		
		@Override
		public void visit(MethodVisitor writer){
			writer.visitInvokeDynamicInsn(
				callingSignature.name(),
				InvokeOp.makeFunSig(callingReturnType, callingSignature.args(), false),
				bootstrapHandle,
				bootstrapArgs
			);
		}
	}
	
	record Increment(Number val, BaseType type) implements Insn{
		
		public static Increment simulate(TypeStack stack, int val) throws MalformedJorth{
			var      type = stack.peekLast();
			BaseType typ;
			if(type.equals(GenericType.INT) || type.equals(GenericType.BYTE) ||
			   type.equals(GenericType.SHORT) || type.equals(GenericType.CHAR)){
				typ = BaseType.INT;
			}else if(type.equals(GenericType.LONG)) typ = BaseType.LONG;
			else if(type.equals(GenericType.FLOAT)) typ = BaseType.FLOAT;
			else if(type.equals(GenericType.DOUBLE)) typ = BaseType.DOUBLE;
			else{
				throw new IllegalArgumentException("Cannot increment stack value of type: " + type + " by int");
			}
			return new Increment(val, typ);
		}
		public static Increment simulate(TypeStack stack, double val) throws MalformedJorth{
			var      type = stack.peekLast();
			BaseType typ;
			if(type.equals(GenericType.DOUBLE)) typ = BaseType.DOUBLE;
			else{
				throw new IllegalArgumentException("Cannot increment stack value of type: " + type + " by double");
			}
			return new Increment(val, typ);
		}
		
		@Override
		public void visit(MethodVisitor writer){
			switch(type){
				case OBJ, VOID, CHAR, BYTE, SHORT, BOOLEAN -> throw new IllegalStateException();
				case INT -> {
					// pop top int, add constant
					IVal.emit(writer, val.intValue());
					writer.visitInsn(IADD);
				}
				case LONG -> {
					LVal.emit(writer, val.longValue());
					writer.visitInsn(LADD);
				}
				case FLOAT -> {
					FVal.emit(writer, val.floatValue());
					writer.visitInsn(FADD);
				}
				case DOUBLE -> {
					DVal.emit(writer, val.doubleValue());
					writer.visitInsn(DADD);
				}
			}
		}
	}
	
	void visit(MethodVisitor writer);
}
