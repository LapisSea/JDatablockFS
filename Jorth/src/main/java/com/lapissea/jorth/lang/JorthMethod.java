package com.lapissea.jorth.lang;

import com.lapissea.jorth.JorthCompiler;
import com.lapissea.jorth.MalformedJorthException;
import com.lapissea.util.LogUtil;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.TextUtil;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.objectweb.asm.Opcodes.*;

public class JorthMethod{
	
	public static class Stack{
		private final List<GenType> data=new ArrayList<>();
		
		private void push(GenType type){
			if(type.typeName().contains("/")) throw new RuntimeException(type.toString());
			data.add(Objects.requireNonNull(type));
		}
		private void insert(int offset, GenType type){
			if(type.typeName().contains("/")) throw new RuntimeException(type.toString());
			data.add(data.size()-(offset+1), Objects.requireNonNull(type));
		}
		private GenType pop(){
			return data.remove(data.size()-1);
		}
		public GenType peek(int el){
			return data.get(data.size()-(el+1));
		}
		public GenType peek(){
			return peek(0);
		}
		public int size(){
			return data.size();
		}
		
		@Override
		public String toString(){
			return TextUtil.toString(data);
		}
	}
	
	private final MethodVisitor currentMethod;
	private final String        className;
	private final GenType       returnType;
	private final boolean       isStatic;
	
	private final Stack typeStack=new Stack();
	
	public JorthMethod(MethodVisitor dest, String className, GenType returnType, boolean isStatic){
		this.currentMethod=dest;
		this.className=className;
		this.returnType=returnType;
		this.isStatic=isStatic;
	}
	
	private void pushTypeStack(GenType type){
		typeStack.push(type);
	}
	private GenType popTypeStack(){
		return typeStack.pop();
	}
	private GenType peekTypeStack(){
		return typeStack.peek();
	}
	
	
	public void start(){
		currentMethod.visitCode();
	}
	public void end(){
		currentMethod.visitMaxs(0, 0);
		currentMethod.visitEnd();
	}
	
	public void invoke(Class<?> clas, String methodName){
		throw new NotImplementedException();
	}
	
	public void invoke(Method method) throws MalformedJorthException{
		CallType type;
		
		if(Modifier.isStatic(method.getModifiers())){
			type=CallType.STATIC;
		}else if(Modifier.isPrivate(method.getModifiers())){
			type=CallType.SPECIAL;
		}else{
			type=CallType.VIRTUAL;
		}
		
		invoke(type, Utils.undotify(method.getDeclaringClass().getName()), method.getName(), Arrays.stream(method.getGenericParameterTypes()).map(this::getGenType).toList(), getGenType(method.getGenericReturnType()), method.getDeclaringClass().isInterface());
	}
	
	private GenType getGenType(Type typ){
		if(typ instanceof Class<?> c) return new GenType(c.getName());
		throw new NotImplementedException(typ.getClass().getName());
	}
	
	public void invoke(CallType type, String undottedClassName, String methodName, List<GenType> args, GenType returnType, boolean isInterface) throws MalformedJorthException{
		
		popAndCheckArguments(undottedClassName, methodName, args);
		
		if(type!=CallType.STATIC){
			var owner=popTypeStack();
			if(!undottedClassName.equals(Utils.undotify(owner.typeName()))){
				if(!undottedClassName.equals("java/lang/Object")){
					throw new MalformedJorthException("Method "+methodName+" belongs to "+undottedClassName+" but got "+owner);
				}
			}
		}
		
		pushTypeStack(returnType);
		
		var sig="("+args.stream().map(Utils::genericSignature).collect(Collectors.joining())+")"+Utils.genericSignature(returnType);
		
		invokeRaw(type, undottedClassName, methodName, sig, isInterface);
	}
	
	private void popAndCheckArguments(String className, String methodName, List<GenType> args) throws MalformedJorthException{
		for(int i=args.size()-1;i>=0;i--){
			var popped=popTypeStack();
			if(!popped.equals(args.get(i))){
				if(!args.get(i).typeName().equals("java/lang/Object")){
					throw new MalformedJorthException("Argument "+i+" in "+className+"#"+methodName+" is "+args.get(i)+" but got "+popped);
				}
			}
		}
	}
	
	private void invokeRaw(CallType type, String undottedClassName, String methodName, String signature, boolean isInterface){
		currentMethod.visitMethodInsn(type.op, undottedClassName, methodName, signature, isInterface);
	}
	
	public void callInit(List<GenType> args) throws MalformedJorthException{
		popAndCheckArguments("", "", args);
		var owner=popTypeStack();
		var nam  =Utils.undotify(owner.typeName());
		var sig  =args.stream().map(Utils::genericSignature).collect(Collectors.joining("", "(", ")V"));
		invokeRaw(CallType.SPECIAL, nam, "<init>", sig, false);
	}
	
	public void swap(){
		var top     =popTypeStack();
		var belowTop=popTypeStack();
		
		pushTypeStack(top);
		pushTypeStack(belowTop);
		
		switch(top.type().slotCount){
			case 1-> {
				switch(belowTop.type().slotCount){
					case 1-> currentMethod.visitInsn(Opcodes.SWAP);
					case 2->{
						currentMethod.visitInsn(Opcodes.DUP_X2);
						currentMethod.visitInsn(Opcodes.POP);
					}
					default -> throw new NotImplementedException(belowTop.toString());
				}
			}
			case 2->{
				switch(belowTop.type().slotCount){
					case 1-> currentMethod.visitInsn(Opcodes.DUP2_X1);
					case 2-> currentMethod.visitInsn(Opcodes.DUP2_X2);
					default -> throw new NotImplementedException(belowTop.toString());
				}
			}
			default -> throw new NotImplementedException(top.toString());
		}
	}
	
	public void loadString(String str){
		currentMethod.visitLdcInsn(str);
		pushTypeStack(GenType.STRING);
	}
	
	public void getFieldIns(String owner, String name, GenType fieldType){
		currentMethod.visitFieldInsn(GETFIELD, Utils.undotify(owner), name, Utils.genericSignature(fieldType));
		pushTypeStack(fieldType);
	}
	
	public void setFieldIns(String owner, String name, GenType fieldType){
		currentMethod.visitFieldInsn(PUTFIELD, Utils.undotify(owner), name, Utils.genericSignature(fieldType));
		popTypeStack();
		popTypeStack();
	}
	
	public void loadArgument(JorthCompiler.FunctArg arg){
		currentMethod.visitVarInsn(arg.type().type().loadOp, arg.index()+(isStatic?0:1));
		pushTypeStack(arg.type());
	}
	
	public void loadThis(){
		currentMethod.visitVarInsn(ALOAD, 0);
		pushTypeStack(new GenType(className));
	}
	
	public void returnOp() throws MalformedJorthException{
		if(returnType!=null){
			var popped=popTypeStack();
			if(!popped.equals(returnType)) throw new MalformedJorthException("Method returns "+returnType+" but "+popped+" is on stack");
			currentMethod.visitInsn(ARETURN);
		}else{
//			if(!typeStack.isEmpty())throw new
			currentMethod.visitInsn(RETURN);
		}
	}
	
	public void newObject(Class<?> clas) throws MalformedJorthException{
		newObject(clas.getName(), null);
	}
	public void newObject(Class<?> clas, List<GenType> args) throws MalformedJorthException{
		newObject(clas.getName(), args);
	}
	public void newObject(String className) throws MalformedJorthException{
		newObject(className, null);
	}
	public void newObject(String className, List<GenType> args) throws MalformedJorthException{
		if(className.contains("/")) throw new RuntimeException(className);
		currentMethod.visitTypeInsn(NEW, Utils.undotify(className));
		pushTypeStack(new GenType(className));
		
		if(args!=null){
			dup();
			callInit(args);
		}
	}
	/**
	 * ... a, b
	 * ... a, b, a, b
	 * */
	public void dupAB(){
		var a=typeStack.peek(0);
		var b=typeStack.peek(1);
		if(a.type().slotCount!=1)throw new NotImplementedException();
		if(b.type().slotCount!=1)throw new NotImplementedException();
		pushTypeStack(b);
		pushTypeStack(a);
		currentMethod.visitInsn(DUP2);
	}
	/**
	 * ... a
	 * ... a, a
	 * */
	public void dup(){
		if(peekTypeStack().type().slotCount!=1)throw new NotImplementedException();
		pushTypeStack(peekTypeStack());
		currentMethod.visitInsn(DUP);
	}
	
	/**
	 * ... a, b
	 * ... b, a, b
	 * */
	public void dupTo1Below(){
		if(peekTypeStack().type().slotCount!=1)throw new NotImplementedException();
		typeStack.insert(1, peekTypeStack());
		currentMethod.visitInsn(DUP_X1);
	}
	
	public Stack getStack(){
		return typeStack;
	}
	public void add() throws MalformedJorthException{
		var a=popTypeStack();
		var b=popTypeStack();
		
		for(int i=0;i<2;i++){
			
			switch(a.type()){
				case INT -> {
					switch(b.type()){
						case INT -> {
							currentMethod.visitInsn(IADD);
							pushTypeStack(new GenType(int.class.getName()));
							return;
						}
					}
				}
			}
			
			var a1=a;
			a=b;
			b=a1;
		}
		throw new MalformedJorthException("Unable to add "+a+" and "+b);
	}
}
