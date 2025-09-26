package com.lapissea.jorth.lang.type;

import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import com.lapissea.jorth.lang.Endable;
import com.lapissea.jorth.lang.info.FunctionInfo;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.UtilL;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.objectweb.asm.Opcodes.*;

public final class FunctionGen implements Endable, FunctionInfo{
	
	public record ArgInfo(JType type, String name){ }
	
	private record Arg(JType type, BaseType info, String name, int accessIndex){ }
	
	private class CodePath{
		private final TypeStack stack;
		
		private boolean ended;
		
		private final Label endLabel = new Label();
		
		public CodePath(MethodVisitor mv, TypeStack stack){
			this.stack = stack;
		}
		
		public void loadThisIns(){
			writer.visitVarInsn(ALOAD, 0);
			stack.push(new GenericType(owner.name));
		}
		
		public void getFieldIns(FieldInfo field) throws MalformedJorth{
			var owner = field.owner();
			var type  = field.type().asGeneric().withoutArgs();
			
			if(!field.isStatic()){
				var stackOwnerType = stack.pop().raw();
				if(!stackOwnerType.instanceOf(typeSource, owner)){
					throw new ClassCastException(stackOwnerType + " not compatible with " + owner);
				}
			}
			
			writer.visitFieldInsn(field.isStatic()? GETSTATIC : GETFIELD, owner.slashed(), field.name(), type.jvmDescriptorStr());
			stack.push(type);
		}
		
		public void setFieldIns(FieldInfo field) throws MalformedJorth{
			var owner = field.owner();
			var type  = field.type().asGeneric().withoutArgs();
			
			stack.requireElements(field.isStatic()? 1 : 2);
			
			var valueType = stack.pop().withoutArgs();
			
			if(!valueType.instanceOf(typeSource, type)){
				throw new ClassCastException(valueType + " not compatible with " + type);
			}
			
			if(!field.isStatic()){
				var stackOwnerType = stack.pop().raw();
				if(!stackOwnerType.instanceOf(typeSource, owner)){
					throw new ClassCastException(stackOwnerType + " not compatible with " + owner);
				}
			}
			
			writer.visitFieldInsn(field.isStatic()? PUTSTATIC : PUTFIELD, owner.slashed(), field.name(), type.jvmDescriptorStr());
		}
		
		public void loadArgumentIns(Arg arg){
			writer.visitVarInsn(arg.info.loadOp, arg.accessIndex());
			stack.push(arg.type.asGeneric());
		}
		
		public void end() throws MalformedJorth{
			if(ended) return;
			ended = true;
			
			BaseType type;
			
			if(returnType != null){
				var ret    = returnType.asGeneric();
				var popped = stack.pop();
				if(!popped.instanceOf(owner.typeSource, ret)){
					throw new MalformedJorth("Method returns " + ret + " but " + popped + " is on stack");
				}
				type = popped.getBaseType();
			}else{
				if(!stack.isEmpty()){
					throw new MalformedJorth("Returning nothing (void) but there are values " + stack + " on the stack");
				}
				type = BaseType.VOID;
			}
			
			writer.visitInsn(type.returnOp);
		}
		
		public void swap() throws MalformedJorth{
			stack.requireElements(2);
			var top      = stack.pop();
			var belowTop = stack.pop();
			
			stack.push(top);
			stack.push(belowTop);
			
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
		public void dup() throws MalformedJorth{
			var type = stack.pop();
			stack.push(type);
			stack.push(type);
			var bt = type.getBaseType();
			writer.visitInsn(switch(bt.slots){
				case 1 -> DUP;
				case 2 -> DUP2;
				default -> throw new MalformedJorth("Illegal base type: " + bt);
			});
		}
		public void pop() throws MalformedJorth{
			var bt = stack.pop().getBaseType();
			writer.visitInsn(switch(bt.slots){
				case 1 -> POP;
				case 2 -> POP2;
				default -> throw new MalformedJorth("Illegal base type: " + bt);
			});
		}
		public void cast(GenericType type) throws MalformedJorth{
			var stackType = stack.pop();
			
			if(type.equals(stackType)){
				stack.push(type);
				return;
			}
			
			if(stackType.getBaseType() != BaseType.OBJ && type.getBaseType() != BaseType.OBJ){
				emitPrimitiveCast(stackType, type);
				stack.push(type);
				return;
			}
			
			if(!type.instanceOf(typeSource, stackType)){
				throw new MalformedJorth("Can not cast " + stackType + " to " + type);
			}
			stack.push(type);
			writer.visitTypeInsn(CHECKCAST, type.dims() == 0? type.raw().slashed() : type.jvmDescriptorStr());
		}
		
		private void emitPrimitiveCast(GenericType from, GenericType to){
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
				throw new IllegalArgumentException("Unsupported primitive cast: " + from + " -> " + to);
			}
		}
		
	}
	
	private final ClassGen        owner;
	private final String          name;
	private final EnumSet<Access> access;
	private final Visibility      visibility;
	
	private final MethodVisitor writer;
	private final TypeSource    typeSource;
	
	private final JType                      returnType;
	private final LinkedHashMap<String, Arg> args;
	
	private final ArrayDeque<CodePath> codeInfo = new ArrayDeque<>();
	
	public FunctionGen(ClassGen owner, String name, Visibility visibility, Set<Access> access, JType returnType, Collection<ArgInfo> args, List<AnnGen> anns) throws MalformedJorth{
		this.owner = owner;
		this.name = name;
		this.access = access.isEmpty()? EnumSet.noneOf(Access.class) : EnumSet.copyOf(access);
		this.returnType = returnType;
		this.typeSource = owner.typeSource;
		this.visibility = visibility;
		
		if(returnType != null) typeSource.byType(returnType.asGeneric());
		for(ArgInfo value : args){
			GenericType typ;
			try{
				typ = value.type.asGeneric();
			}catch(UnsupportedOperationException e){
				continue;
			}
			typeSource.byType(typ);
		}
		
		this.args = LinkedHashMap.newLinkedHashMap(args.size());
		
		int counter = isStatic()? 0 : 1;
		for(var arg : args){
			var info = arg.type.getBaseType();
			this.args.put(arg.name, new Arg(arg.type, info, arg.name, counter));
			counter += info.slots;
		}
		
		if(owner.type == ClassType.INTERFACE && !access.contains(Access.STATIC)){
			this.access.add(Access.ABSTRACT);
		}
		if(name.equals("<clinit>")){
			this.access.add(Access.STATIC);
		}
		
		var accessFlags = visibility.flag;
		for(var acc : this.access){
			accessFlags |= acc.flag;
		}
		
		var argTypes = new ArrayList<JType>(args.size());
		for(ArgInfo value : args){
			argTypes.add(value.type);
		}
		
		var descriptor = makeFunSig(returnType, argTypes, false);
		var signature  = makeFunSig(returnType, argTypes, true);
		
		if(descriptor.equals(signature)) signature = null;
		
		writer = owner.writer.visitMethod(accessFlags, name, descriptor, signature, null);
		
		ClassGen.writeAnnotations(anns, writer::visitAnnotation);
		
		if(!this.access.contains(Access.ABSTRACT)){
			codeInfo.add(new CodePath(writer, new TypeStack(null)));
		}
	}
	
	@Override
	public void end() throws MalformedJorth{
		if(codeInfo.isEmpty()){
			writer.visitEnd();
			return;
		}
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
	
	public void getStaticOp(ClassName owner, String member) throws MalformedJorth{
		var info = typeSource.byName(owner);
		doGet(member, info);
	}
	
	public void getArgOp(String member) throws MalformedJorth{
		var arg = args.get(member);
		if(arg == null){
			throw new MalformedJorth("Argument " + member + " does not exist");
		}
		code().loadArgumentIns(arg);
	}
	
	public void getThisOp(String member) throws MalformedJorth{
		loadThisIns();
		if(member.equals("this")) return;
		doGet(member, owner);
	}
	
	private void doGet(String member, ClassInfo owner) throws MalformedJorth{
		var memberInfo = owner.getField(member);
		code().getFieldIns(memberInfo);
	}
	
	public void setElementOP() throws MalformedJorth{
		var stack = code().stack;
		
		var element = stack.pop();
		var index   = stack.pop();
		var array   = stack.pop();
		
		if(!index.getBaseType().arrayIndexCompatible){
			throw new MalformedJorth(index + " can not be used as array index");
		}
		if(array.dims() == 0){
			throw new MalformedJorth(array + " is not an array");
		}
		if(!element.instanceOf(typeSource, array.withDims(array.dims() - 1))){
			throw new MalformedJorth("can not store " + element + " in " + array);
		}
		
		writer.visitInsn(element.getBaseType().arrayStoreOP);
	}
	
	public void setInstanceOp(String member) throws MalformedJorth{
		var stack = code().stack;
		stack.requireElements(2);
		doSet(member, typeSource.byType(stack.peek(stack.size() - 2)));
	}
	public void setStaticOp(ClassName owner, String member) throws MalformedJorth{
		doSet(member, typeSource.byName(owner));
	}
	private void doSet(String member, ClassInfo info) throws MalformedJorth{
		var memberInfo = info.getField(member);
		code().setFieldIns(memberInfo);
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
	
	private CodePath code(){
		assert codeInfo.peekLast() != null;
		return codeInfo.peekLast();
	}
	public TypeStack getStack(){
		return code().stack;
	}
	
	public void superOp(List<JType> args) throws MalformedJorth{
		if(!name.equals("<init>")){
			throw new NotImplementedException("Super on non constructor not implemented");
		}
		var sup = owner.superType();
		invokeOp(sup.getFunction(new Signature("<init>", args)), true);
	}
	
	public void newOp(GenericType type) throws MalformedJorth{
		var stack = code().stack;
		if(type.dims()>0){
			var arraySize = stack.pop();
			if(!List.of(int.class, short.class, byte.class).contains(arraySize.getBaseType().type)){
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
		if(function.isStatic()){
			callOp = INVOKESTATIC;
		}else if(owner.type() == ClassType.INTERFACE){
			callOp = INVOKEINTERFACE;
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
		
		var returnType = function.returnType();
		var argTypes   = function.argumentTypes();
		
		var stack = code().stack;
		
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
			code().stack.push(returnType.asGeneric());
		}
		
		var signature = makeFunSig(returnType, argTypes, false);
		
		writer.visitMethodInsn(callOp, ownerName, name, signature, ownerType == ClassType.INTERFACE);
	}
	
	public static final class VirtualCallInfo{
		public record SType(JType type, Object value){ }
		
		public ClassName   bootstrapClass      = null;
		public String      bootstrapMethod     = null;
		public List<SType> bootstrapStaticArgs = new ArrayList<>();
		
		public String      callingMethod           = null;
		public List<JType> callingMethodArgs       = new ArrayList<>();
		public JType       callingMethodReturnType = null;
	}
	
	public Endable startVirutalCall(VirtualCallInfo info){
		var mark = code().stack.size();
		return () -> {
			var args = readCallStack(mark);
			if(args.size() != info.callingMethodArgs.size()){
				throw new MalformedJorth(
					"Call args size mismatch:\n\t" +
					"Requested: " + info.callingMethodArgs + "\n\t" +
					"But got:   " + args
				);
			}
			for(int i = 0; i<args.size(); i++){
				var argType = args.get(i);
				var defType = info.callingMethodArgs.get(i);
				if(!argType.asGeneric().instanceOf(typeSource, defType.asGeneric())){
					throw new MalformedJorth(
						"Argument " + i + " does not satisfy type of argument. Is " + argType.asGeneric() + " but " + defType.asGeneric() + " is required"
					);
				}
			}
			
			var bArgs = Stream.concat(
				Stream.of(
					JType.of(MethodHandles.Lookup.class),
					JType.of(String.class),
					JType.of(MethodType.class)
				),
				info.bootstrapStaticArgs.stream().map(VirtualCallInfo.SType::type)
			).toList();
			
			var bc = typeSource.byName(info.bootstrapClass);
			var fns = bc.getFunctionsByName(info.bootstrapMethod).filter(e -> {
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
			var fn = switch(fns.size()){
				case 0 -> {
					throw new MalformedJorth(
						"No valid boostrap function found for " + info.bootstrapClass + "." +
						info.bootstrapMethod + bArgs.stream()
						                            .map(Object::toString)
						                            .collect(Collectors.joining(", ", "(", ")"))
					);
				}
				case 1 -> fns.getFirst();
				default -> {
					throw new MalformedJorth("Ambiguous boostrap function found for " + info.bootstrapClass + "#" + info.bootstrapMethod);
				}
			};
			Handle bsmh = new Handle(
				H_INVOKESTATIC,
				info.bootstrapClass.slashed(),
				info.bootstrapMethod,
				makeFunSig(fn.returnType(), fn.argumentTypes(), false),
				bc.isInterface()
			);
			
			for(int i = 0; i<args.size(); i++){
				code().stack.pop();
			}
			
			if(info.callingMethodReturnType != null){
				code().stack.push(info.callingMethodReturnType.asGeneric());
			}
			
			writer.visitInvokeDynamicInsn(
				info.callingMethod,
				makeFunSig(info.callingMethodReturnType, info.callingMethodArgs, false),
				bsmh,
				info.bootstrapStaticArgs.stream().map(VirtualCallInfo.SType::value).toArray()
			);
		};
	}
	
	public Endable startCall(ClassName staticOwner, String functionName) throws MalformedJorth{
		ClassInfo cInfo;
		if(staticOwner != null){
			cInfo = typeSource.byName(staticOwner);
		}else{
			var stack = code().stack;
			stack.requireElements(1);
			if(stack.isEmpty()) throw new MalformedJorth("Trying to call non static method but there is nothing on stack");
			cInfo = typeSource.byType(stack.peek(stack.size() - 1));
		}
		
		return startCallRaw(cInfo, functionName, false);
	}
	
	public Endable startCallRaw(ClassInfo cInfo, String functionName, boolean superCall){
		var mark = code().stack.size();
		return () -> {
			var args = readCallStack(mark);
			
			FunctionInfo info;
			try{
				info = cInfo.getFunction(new Signature(functionName, args));
			}catch(MalformedJorth e){
				info = cInfo.getFunctionsByName(functionName).filter(f -> {
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
			
			invokeOp(info, superCall);
		};
	}
	private List<JType> readCallStack(int mark) throws MalformedJorth{
		var stack    = code().stack;
		var argCount = stack.size() - mark;
		if(argCount<0) throw new MalformedJorth("Negative stack delta inside arg block");
		var args = new ArrayList<JType>(argCount);
		for(int i = 0; i<argCount; i++){
			args.add(stack.peek(mark + i));
		}
		return args;
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
			if(prim.get().loadOp == ILOAD){
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
		code().stack.push(GenericType.STRING);
		emitStackString(str);
	}
	private void emitStackString(String str){
		writer.visitLdcInsn(str);
	}
	
	public void loadIntOp(int value){
		code().stack.push(GenericType.INT);
		emitStackInt(value);
	}
	
	private void emitStackInt(int value){
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
	public void loadLongOp(long value){
		code().stack.push(GenericType.LONG);
		emitStackLong(value);
	}
	private void emitStackLong(long value){
		switch((int)value){
			case 0 -> writer.visitInsn(LCONST_0);
			case 1 -> writer.visitInsn(LCONST_1);
			default -> writer.visitLdcInsn(value);
		}
	}
	
	public void loadBooleanOp(boolean value){
		code().stack.push(GenericType.BOOL);
		emitStackBoolean(value);
	}
	private void emitStackBoolean(boolean value){
		if(value){
			writer.visitInsn(ICONST_1);
		}else{
			writer.visitInsn(ICONST_0);
		}
	}
	
	public void loadFloatOp(float value){
		code().stack.push(GenericType.FLOAT);
		emitStackFloat(value);
	}
	private void emitStackFloat(float value){
		if(value == 0.0f) writer.visitInsn(FCONST_0);
		else if(value == 1.0f) writer.visitInsn(FCONST_1);
		else if(value == 2.0f) writer.visitInsn(FCONST_2);
		else writer.visitLdcInsn(value);
	}
	public void loadDoubleOp(double value){
		code().stack.push(GenericType.FLOAT);
		emitStackDouble(value);
	}
	private void emitStackDouble(double value){
		if(value == 0.0d) writer.visitInsn(DCONST_0);
		else if(value == 1.0d) writer.visitInsn(DCONST_1);
		else writer.visitLdcInsn(value);
	}
	public void stackIncrement(Number increment){
		var type = code().stack.peekLast();
		if(type.equals(GenericType.INT) || type.equals(GenericType.BYTE) ||
		   type.equals(GenericType.SHORT) || type.equals(GenericType.CHAR)){
			// pop top int, add constant
			emitStackInt(increment.intValue());
			writer.visitInsn(IADD);
		}else if(type.equals(GenericType.LONG)){
			emitStackLong(increment.longValue());
			writer.visitInsn(LADD);
		}else if(type.equals(GenericType.FLOAT)){
			emitStackFloat(increment.floatValue());
			writer.visitInsn(FADD);
		}else if(type.equals(GenericType.DOUBLE)){
			emitStackDouble(increment.doubleValue());
			writer.visitInsn(DADD);
		}else{
			throw new IllegalArgumentException("Cannot increment stack value of type: " + type);
		}
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
	public JType returnType(){
		return returnType;
	}
	@Override
	public List<JType> argumentTypes(){
		return args.values().stream().map(a -> a.type).toList();
	}
	@Override
	public Object defaultEnumValue(){
		return null;
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
	public void swapOp() throws MalformedJorth{
		code().swap();
	}
	public void castOp(GenericType type) throws MalformedJorth{
		code().cast(type);
	}
	
	public void loadClassTypeOp(ClassName clazz){
		var stack = code().stack;
		var typ   = new GenericType(clazz);
		writer.visitLdcInsn(org.objectweb.asm.Type.getType(typ.jvmSignatureStr()));
		
		stack.push(new GenericType(ClassName.of(Class.class), Optional.empty(), 0, List.of(typ)));
	}
}
