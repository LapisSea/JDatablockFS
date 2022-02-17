package com.lapissea.jorth.lang;

import com.lapissea.jorth.JorthCompiler;
import com.lapissea.jorth.MalformedJorthException;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.TextUtil;
import com.lapissea.util.function.UnsafeRunnable;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.*;
import java.util.stream.Collectors;

import static org.objectweb.asm.Opcodes.*;

public class JorthMethod{
	
	public class CodePoint{
		
		public CodePoint prev;
		public CodePoint last;
		
		public final Label asmLabel=new Label();
		
		private Stack stackState;
		
		public void plant(){
			record();
			mv.visitLabel(asmLabel);
		}
		
		public void record(){
			if(stackState!=null) throw new IllegalStateException();
			this.stackState=getStack().clone();
		}
		
		public Stack getStackState(){
			return Objects.requireNonNull(stackState);
		}
		
		public void restoreStack(){
			typeStack=getStackState().clone();
		}
	}
	
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
		
		public void checkTop(GenType... types) throws MalformedJorthException{
			checkTop(Arrays.asList(types));
		}
		
		public void checkTop(List<GenType> types) throws MalformedJorthException{
			if(data.size()<types.size()) throw new MalformedJorthException("Expected type stack of "+types+" but got "+data);
			
			var end=data.subList(data.size()-types.size(), data.size());
			
			if(!end.equals(types)){
				throw new MalformedJorthException("Expected type stack of ... "+end+" but got "+data);
			}
			
		}
		
		@Override
		public Stack clone(){
			var stack=new Stack();
			stack.data.addAll(data);
			return stack;
		}
		@Override
		public boolean equals(Object o){
			return this==o||o instanceof Stack stack&&data.equals(stack.data);
		}
		
		@Override
		public int hashCode(){
			return data.hashCode();
		}
	}
	
	private final LinkedList<Label> branchLabels=new LinkedList<>();
	
	private final JorthCompiler context;
	private final MethodVisitor mv;
	private final String        name;
	private final String        className;
	private final GenType       returnType;
	private final boolean       isStatic;
	
	private Stack typeStack=new Stack();
	
	public JorthMethod(JorthCompiler context, MethodVisitor dest, String name, String className, GenType returnType, boolean isStatic){
		this.context=context;
		this.mv=dest;
		this.name=name;
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
		mv.visitCode();
	}
	public void end(){
		mv.visitMaxs(0, 0);
		mv.visitEnd();
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
		
		invoke(type, method.getDeclaringClass().getName(), method.getName(), Arrays.stream(method.getGenericParameterTypes()).map(this::getGenType).toList(), getGenType(method.getGenericReturnType()), method.getDeclaringClass().isInterface());
	}
	
	private GenType getGenType(Type typ){
		if(typ instanceof Class<?> c) return new GenType(c.getName());
		throw new NotImplementedException(typ.getClass().getName());
	}
	
	public void invoke(CallType type, String className, String methodName, List<GenType> args, GenType returnType, boolean isInterface) throws MalformedJorthException{
		String undottedClassName=Utils.undotify(className);
		
		popAndCheckArguments(className, methodName, args);
		
		check:
		if(type!=CallType.STATIC){
			var owner=popTypeStack();
			if(undottedClassName.equals(Utils.undotify(owner.typeName()))) break check;
			if(undottedClassName.equals("java/lang/Object")) break check;
			
			var clazz=context.getClassInfo(className);
			if(clazz.instanceOf(className)) break check;
			
			throw new MalformedJorthException("Method "+methodName+" belongs to "+className+" but got "+owner);
		}
		
		if(returnType.type()!=Types.VOID){
			pushTypeStack(returnType);
		}
		
		var sig="("+args.stream().map(Utils::genericSignature).collect(Collectors.joining())+")"+Utils.genericSignature(returnType);
		
		invokeRaw(type, undottedClassName, methodName, sig, isInterface);
	}
	
	private void popAndCheckArguments(String className, String methodName, List<GenType> args) throws MalformedJorthException{
		for(int i=args.size()-1;i>=0;i--){
			var popped=popTypeStack();
			var arg   =args.get(i);
			if(arg.instanceOf(context, popped)) continue;
			
			throw new MalformedJorthException("Argument "+i+" in "+className+"#"+methodName+" is "+args.get(i)+" but got "+popped);
		}
	}
	
	private void invokeRaw(CallType type, String undottedClassName, String methodName, String signature, boolean isInterface){
		mv.visitMethodInsn(type.op, undottedClassName, methodName, signature, isInterface);
	}
	
	public void callInit(List<GenType> args) throws MalformedJorthException{
		popAndCheckArguments("", "", args);
		var owner=popTypeStack();
		var nam  =Utils.undotify(owner.typeName());
		var sig  =args.stream().map(Utils::genericSignature).collect(Collectors.joining("", "(", ")V"));
		invokeRaw(CallType.SPECIAL, nam, "<init>", sig, false);
	}
	
	public void pop(){
		var top=popTypeStack();
		
		switch(top.type().slotCount){
			case 1 -> mv.visitInsn(POP);
			case 2 -> mv.visitInsn(POP2);
			default -> throw new NotImplementedException(top.toString());
		}
	}
	
	public void swap(){
		var top     =popTypeStack();
		var belowTop=popTypeStack();
		
		pushTypeStack(top);
		pushTypeStack(belowTop);
		
		switch(top.type().slotCount){
			case 1 -> {
				switch(belowTop.type().slotCount){
					case 1 -> mv.visitInsn(SWAP);
					case 2 -> {
						mv.visitInsn(DUP_X2);
						mv.visitInsn(POP);
					}
					default -> throw new NotImplementedException(belowTop.toString());
				}
			}
			case 2 -> {
				switch(belowTop.type().slotCount){
					case 1 -> {
						mv.visitInsn(DUP2_X1);
						mv.visitInsn(POP2);
					}
					case 2 -> {
						mv.visitInsn(DUP2_X2);
						mv.visitInsn(POP2);
					}
					default -> throw new NotImplementedException(belowTop.toString());
				}
			}
			default -> throw new NotImplementedException(top.toString());
		}
	}
	
	public void loadString(String str){
		mv.visitLdcInsn(str);
		pushTypeStack(GenType.STRING);
	}
	
	public void getFieldIns(String owner, String name, GenType fieldType){
		mv.visitFieldInsn(GETFIELD, Utils.undotify(owner), name, Utils.genericSignature(fieldType.rawType()));
		pushTypeStack(fieldType);
	}
	
	public void setFieldIns(String owner, String name, GenType fieldType){
		mv.visitFieldInsn(PUTFIELD, Utils.undotify(owner), name, Utils.genericSignature(fieldType.rawType()));
		popTypeStack();
		popTypeStack();
	}
	
	public void loadArgument(LocalVariableStack.Variable arg){
		mv.visitVarInsn(arg.type().type().loadOp, arg.accessIndex()+(isStatic?0:1));
		pushTypeStack(arg.type());
	}
	
	public void loadThis(){
		mv.visitVarInsn(ALOAD, 0);
		pushTypeStack(new GenType(className));
	}
	
	public void returnOp() throws MalformedJorthException{
		if(returnType!=null){
			var popped=popTypeStack();
			if(!popped.equals(returnType)) throw new MalformedJorthException("Method returns "+returnType+" but "+popped+" is on stack");
			mv.visitInsn(popped.type().returnOp);
		}else{
			if(typeStack.size()>0) throw new MalformedJorthException("Returning nothing (void) but there are values "+typeStack+" on the stack");
			mv.visitInsn(RETURN);
		}
	}
	
	public void newObject(Class<?> clas){
		newObject(clas.getName());
	}
	public void newObject(String className){
		if(className.contains("/")) throw new RuntimeException(className);
		mv.visitTypeInsn(NEW, Utils.undotify(className));
		pushTypeStack(new GenType(className));
	}
	/**
	 * ... a, b
	 * ... a, b, a, b
	 */
	public void dupAB(){
		var a=typeStack.peek(0);
		var b=typeStack.peek(1);
		if(a.type().slotCount!=1) throw new NotImplementedException();
		if(b.type().slotCount!=1) throw new NotImplementedException();
		pushTypeStack(b);
		pushTypeStack(a);
		mv.visitInsn(DUP2);
	}
	/**
	 * ... a
	 * ... a, a
	 */
	public void dup(){
		if(peekTypeStack().type().slotCount!=1) throw new NotImplementedException();
		pushTypeStack(peekTypeStack());
		mv.visitInsn(DUP);
	}
	
	/**
	 * ... a, b
	 * ... b, a, b
	 */
	public void dupTo1Below(){
		if(peekTypeStack().type().slotCount!=1) throw new NotImplementedException();
		typeStack.insert(1, peekTypeStack());
		mv.visitInsn(DUP_X1);
	}
	
	public String getName(){
		return name;
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
							mv.visitInsn(IADD);
							pushTypeStack(Types.INT.genTyp);
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
	
	
	private void IIToIOp(int op) throws MalformedJorthException{numNumToOp(List.of(Types.BYTE, Types.SHORT, Types.INT), Types.INT, op);}
	private void LLToLOp(int op) throws MalformedJorthException{numNumToOp(List.of(Types.LONG), Types.LONG, op);}
	private void FFToFOp(int op) throws MalformedJorthException{numNumToOp(List.of(Types.FLOAT), Types.FLOAT, op);}
	private void DDToDOp(int op) throws MalformedJorthException{numNumToOp(List.of(Types.DOUBLE), Types.DOUBLE, op);}
	
	private void numNumToOp(List<Types> input, Types result, int op) throws MalformedJorthException{
		for(int i=0;i<2;i++){
			var operand=popTypeStack();
			if(!input.contains(operand.type())) throw new MalformedJorthException("argument "+i+": "+operand+" is not "+input);
		}
		
		mv.visitInsn(op);
		
		pushTypeStack(result.genTyp);
		
	}
	
	public void integerAdd() throws MalformedJorthException{IIToIOp(IADD);}
	public void integerSub() throws MalformedJorthException{IIToIOp(ISUB);}
	public void integerDiv() throws MalformedJorthException{IIToIOp(IDIV);}
	public void integerMul() throws MalformedJorthException{IIToIOp(IMUL);}
	
	public void floatAdd() throws MalformedJorthException  {FFToFOp(FADD);}
	public void floatSub() throws MalformedJorthException  {FFToFOp(FSUB);}
	public void floatDiv() throws MalformedJorthException  {FFToFOp(FDIV);}
	public void floatMul() throws MalformedJorthException  {FFToFOp(FMUL);}
	
	public void longAdd() throws MalformedJorthException   {LLToLOp(LADD);}
	public void longSub() throws MalformedJorthException   {LLToLOp(LSUB);}
	public void longDiv() throws MalformedJorthException   {LLToLOp(LDIV);}
	public void longMul() throws MalformedJorthException   {LLToLOp(LMUL);}
	
	public void doubleAdd() throws MalformedJorthException {DDToDOp(DADD);}
	public void doubleSub() throws MalformedJorthException {DDToDOp(DSUB);}
	public void doubleDiv() throws MalformedJorthException {DDToDOp(DDIV);}
	public void doubleMul() throws MalformedJorthException {DDToDOp(DMUL);}
	
	private void intTo(int op, Types type) throws MalformedJorthException{
		var typ=popTypeStack();
		if(typ.type()!=Types.INT) throw new MalformedJorthException(typ+" is not int");
		pushTypeStack(type.genTyp);
		
		mv.visitInsn(op);
	}
	
	public void intToByte() throws MalformedJorthException  {intTo(I2B, Types.BYTE);}
	public void intToShort() throws MalformedJorthException {intTo(I2S, Types.SHORT);}
	public void intToLong() throws MalformedJorthException  {intTo(I2L, Types.LONG);}
	public void intToFloat() throws MalformedJorthException {intTo(I2F, Types.FLOAT);}
	public void intToDouble() throws MalformedJorthException{intTo(I2D, Types.DOUBLE);}
	
	public void growToInt() throws MalformedJorthException{
		var typ=popTypeStack();
		if(!List.of(Types.BYTE, Types.CHAR, Types.SHORT).contains(typ.type())) throw new MalformedJorthException(typ+" is not a smaller int");
		pushTypeStack(Types.INT.genTyp);
	}
	
	
	public void nullBlock(UnsafeRunnable<MalformedJorthException> ifNull, UnsafeRunnable<MalformedJorthException> ifNotNull) throws MalformedJorthException{
		
		var typ=popTypeStack();
		if(typ.type()!=Types.OBJECT) throw new MalformedJorthException(typ+" is not an object");
		
		var endLabel    =new Label();
		var nonNullLabel=new Label();
		mv.visitJumpInsn(IFNONNULL, nonNullLabel);
		
		var branchStack=getStack().clone();
		
		ifNull.run();
		
		var afterBranchStack1=typeStack.clone();
		typeStack=branchStack;
		
		mv.visitJumpInsn(GOTO, endLabel);
		
		mv.visitLabel(nonNullLabel);
		ifNotNull.run();
		
		var afterBranchStack2=getStack().clone();
		
		if(!afterBranchStack1.equals(afterBranchStack2)){
			throw new MalformedJorthException("Both blocks need to return the same stack\n"+
			                                  afterBranchStack1+"\n"+
			                                  afterBranchStack2);
		}
		
		mv.visitLabel(endLabel);
	}
	
	public void jumpToIfFalse(CodePoint point) throws MalformedJorthException{
		
		var typ=popTypeStack();
		if(typ.type()!=Types.BOOLEAN) throw new MalformedJorthException(typ+" is not a boolean");
		
		mv.visitJumpInsn(IFEQ, point.asmLabel);
		
	}
	
	public void jumpTo(CodePoint point){
		mv.visitJumpInsn(GOTO, point.asmLabel);
	}
	
	public void loadInt(int value){
		pushTypeStack(Types.INT.genTyp);
		switch(value){
			case 0 -> mv.visitInsn(ICONST_0);
			case 1 -> mv.visitInsn(ICONST_1);
			case 2 -> mv.visitInsn(ICONST_2);
			case 3 -> mv.visitInsn(ICONST_3);
			case 4 -> mv.visitInsn(ICONST_4);
			case 5 -> mv.visitInsn(ICONST_5);
			default -> mv.visitIntInsn(SIPUSH, value);
		}
	}
	
	public void loadBoolean(boolean value){
		pushTypeStack(Types.BOOLEAN.genTyp);
		if(value){
			mv.visitInsn(ICONST_1);
		}else{
			mv.visitInsn(ICONST_0);
		}
	}
	
	
	public void intIntEqualityToBool() throws MalformedJorthException{
		GenType typ;
		if((typ=popTypeStack()).type()!=Types.INT) throw new MalformedJorthException(typ+" (arg1) is not an int");
		if((typ=popTypeStack()).type()!=Types.INT) throw new MalformedJorthException(typ+" (arg2) is not an int");
		pushTypeStack(Types.BOOLEAN.genTyp);
		
		comparisonToBool(IF_ICMPNE);
	}
	
	private void comparisonToBool(int ifNotOp){
		Label falseL=new Label();
		mv.visitJumpInsn(ifNotOp, falseL);
		mv.visitInsn(ICONST_1);
		Label endL=new Label();
		mv.visitJumpInsn(GOTO, endL);
		mv.visitLabel(falseL);
		mv.visitInsn(ICONST_0);
		mv.visitLabel(endL);
	}
	
	public boolean isStatic(){
		return isStatic;
	}
}
