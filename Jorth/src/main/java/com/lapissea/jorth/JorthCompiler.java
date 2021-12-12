package com.lapissea.jorth;

import com.lapissea.jorth.lang.GenType;
import com.lapissea.jorth.lang.Token;
import com.lapissea.jorth.lang.Visibility;
import com.lapissea.util.NotImplementedException;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.*;
import java.util.stream.Collectors;

import static org.objectweb.asm.Opcodes.*;

public class JorthCompiler{
	
	
	private final Deque<Token> rawTokens=new LinkedList<>();
	
	private int lastLine;
	
	private String               className;
	private Map<String, GenType> classFields=new HashMap<>();
	
	private ClassWriter   currentClass;
	private MethodVisitor currentMethod;
	private boolean       isStatic;
	
	private GenType classExtension=new GenType(Object.class.getName(), List.of());
	
	private GenType returnType;
	
	private record FunctArg(int index, String name, GenType type){}
	
	private Map<String, FunctArg> arguments=new HashMap<>();
	
	private List<GenType> classInterfaces=new ArrayList<>();
	private Visibility    classVisibility;
	private boolean       addedInit;
	private boolean       addedClinit;
	private Visibility    visibility     =Visibility.PUBLIC;
	
	private boolean isEmpty(){
		return rawTokens.isEmpty();
	}
	private Token pop(){
		return rawTokens.removeLast();
	}
	private Token peek(){
		return rawTokens.getLast();
	}
	private void push(Token token){
		rawTokens.addLast(token);
	}
	
	private void consumeRawToken(JorthWriter writer, Token token) throws MalformedJorthException{
		if(consume(writer, token)){
			functionLogLine(token);
		}
	}
	
	private void functionLogLine(Token token){
	}
	
	private boolean consume(JorthWriter writer, Token token) throws MalformedJorthException{
		
		// string literal
		if(token.isStringLiteral()){
			currentMethod.visitLdcInsn(token.getStringLiteralValue());
			return true;
		}
		
		switch(token.lower()){
			case "static" -> {
				isStatic=true;
				return true;
			}
			case "define" -> {
				requireTokenCount(2);
				var tokenName=pop();
				var value    =pop();
				writer.addDefinition(tokenName.source, value.source);
				return true;
			}
			case "extends" -> {
				classExtension=readGenericType();
				return true;
			}
			case "returns" -> {
				returnType=readGenericType();
				return true;
			}
			case "arg" -> {
				var name=pop();
				arguments.put(name.source, new FunctArg(arguments.size(), name.source, readGenericType()));
				return true;
			}
			case "implements" -> {
				classInterfaces.add(readGenericType());
				return true;
			}
			case "visibility" -> {
				requireTokenCount(1);
				this.visibility=pop().asVisibility();
				return true;
			}
			case "set" -> {
				requireTokenCount(2);
				var name =pop();
				var owner=pop();
				
				switch(owner.source){
					case "this" -> {
						loadThis();
						swap();
						var type=classFields.get(name.source);
						if(type==null) throw new MalformedJorthException(name+" does not exist in "+currentClass);
						
						setFieldIns(className, name.source, type);
					}
					case "<arg>" -> {
						throw new MalformedJorthException("can't set arguments! argument: "+name);
					}
					default -> throw new NotImplementedException("don't know how to load from "+owner);
				}
				
				return true;
			}
			case "get" -> {
				requireTokenCount(2);
				var name =pop();
				var owner=pop();
				
				
				switch(owner.source){
					case "this" -> {
						loadThis();
						
						var type=classFields.get(name.source);
						if(type==null) throw new MalformedJorthException(name+" does not exist in "+currentClass);
						
						getFieldIns(className, name.source, type);
					}
					case "<arg>" -> {
						var arg=arguments.values()
						                 .stream()
						                 .filter(a->a.name.equals(name.source))
						                 .findAny();
						if(arg.isEmpty()){
							throw new MalformedJorthException("argument "+name+" does not exist");
						}
						loadArgument(arg.get());
					}
					default -> throw new NotImplementedException("don't know how to load from "+owner);
				}
				
				return true;
			}
			case "field" -> {
				requireTokenCount(2);
				var name=pop().source;
				var type=readGenericType();
				
				if(classFields.containsKey(name)){
					throw new MalformedJorthException("field "+name+" already exists!");
				}
				classFields.put(name, type);
				
				currentClass.visitField(visibility.opCode, name, genericSignature(new GenType(type.typeName, List.of())), genericSignature(type), null);
				visibility=Visibility.PUBLIC;
				return true;
			}
			case "start" -> {
				requireTokenCount(1);
				var subject=pop();
				
				
				switch(subject.lower()){
					case "class" -> {
						if(currentClass!=null) throw new MalformedJorthException("Class "+currentClass+" already started!");
						requireTokenCount(1);
						className=pop().source;
						
						classVisibility=visibility;
						currentClass=new ClassWriter(ClassWriter.COMPUTE_MAXS|ClassWriter.COMPUTE_FRAMES);
						
						
						var signature=new StringBuilder();
						signature.append(genericSignature(classExtension));
						for(GenType sig : classInterfaces){
							signature.append(genericSignature(sig));
						}
						
						var interfaces=classInterfaces.stream().map(sig->undotify(sig.typeName)).toArray(String[]::new);
						if(interfaces.length==0) interfaces=null;
						
						var nam=undotify(className);
						var ext=undotify(classExtension.typeName);
						
						currentClass.visit(V11, classVisibility.opCode+ACC_SUPER, nam, signature.toString(), ext, interfaces);
						
						visibility=Visibility.PUBLIC;
						
					}
					case "function" -> {
						requireTokenCount(1);
						var functionName=pop();
						
						String returnStr;
						if(returnType!=null) returnStr=genericSignature(returnType);
						else returnStr="V";
						
						var staticOo=isStatic?ACC_STATIC:0;
						
						var args=arguments.values()
						                  .stream()
						                  .sorted(Comparator.comparingInt(FunctArg::index))
						                  .map(FunctArg::type)
						                  .map(this::genericSignature)
						                  .collect(Collectors.joining());
						
						currentMethod=currentClass.visitMethod(visibility.opCode+staticOo, functionName.source, "("+args+")"+returnStr, null, null);
						currentMethod.visitCode();
						
						if("<clinit>".equals(functionName.source)){
							addedClinit=true;
						}
						if("<init>".equals(functionName.source)){
							addedInit=true;
							currentMethod.visitVarInsn(ALOAD, 0);
							currentMethod.visitMethodInsn(INVOKESPECIAL, undotify(classExtension.typeName), functionName.source, "()V", false);
						}
					}
					default -> throw new MalformedJorthException("Unknown subject "+subject+". Can not start it");
				}
				
				return true;
			}
			case "end" -> {
				requireTokenCount(1);
				var subject=pop();
				switch(subject.lower()){
					case "function" -> {
						if(returnType!=null){
							currentMethod.visitInsn(ARETURN);
						}else{
							currentMethod.visitInsn(RETURN);
						}
						
						currentMethod.visitMaxs(0, 0);
						currentMethod.visitEnd();
						currentMethod=null;
						returnType=null;
						arguments.clear();
					}
					
					default -> throw new MalformedJorthException("Unknown subject "+subject+". Can not end it");
				}
				return true;
			}
		}
		
		push(token);
		return false;
	}
	
	private void swap(){
		currentMethod.visitInsn(Opcodes.SWAP);
	}
	
	private void getFieldIns(String owner, String name, GenType fieldType){
		currentMethod.visitFieldInsn(GETFIELD, undotify(owner), name, genericSignature(fieldType));
	}
	private void setFieldIns(String owner, String name, GenType fieldType){
		currentMethod.visitFieldInsn(PUTFIELD, undotify(owner), name, genericSignature(fieldType));
	}
	
	private void loadArgument(FunctArg arg){
		currentMethod.visitVarInsn(ALOAD, arg.index+1);
	}
	
	private void loadThis(){
		currentMethod.visitVarInsn(ALOAD, 0);
	}
	
	private GenType readGenericType() throws MalformedJorthException{
		requireTokenCount(1);
		var           typeName=pop();
		List<GenType> args    =new ArrayList<>(4);
		
		if(!isEmpty()){
			var first=peek();
			if("]".equals(first.source)){
				pop();
				
				while(true){
					var token=peek();
					if("[".equals(token.source)){
						pop();
						break;
					}
					var subType=readGenericType();
					args.add(subType);
				}
			}
		}
		
		return new GenType(typeName.source, List.copyOf(args));
	}
	
	private String genericSignature(GenType sig){
		var primitive=switch(sig.typeName){
			case "boolean" -> "Z";
			case "void" -> "V";
			case "char" -> "C";
			case "byte" -> "B";
			case "short" -> "S";
			case "int" -> "I";
			case "long" -> "J";
			case "float" -> "F";
			case "double" -> "D";
			default -> null;
		};
		if(primitive!=null){
			return primitive;
		}
		
		String argStr;
		if(sig.args.isEmpty()) argStr="";
		else{
			argStr=sig.args.stream().map(this::genericSignature).collect(Collectors.joining("", "<", ">"));
		}
		return "L"+undotify(sig.typeName)+argStr+";";
	}
	
	private String undotify(String className){
		return className.replace('.', '/');
	}
	
	private void requireTokenCount(int minWordCount) throws MalformedJorthException{
		if(rawTokens.size()<minWordCount) throw new MalformedJorthException("token requires at least "+minWordCount+" words!");
	}
	
	public JorthWriter writeCode(){
		return new JorthWriter(lastLine, this::consumeRawToken);
	}
	
	
	public byte[] classBytecode(){
		
		if(!addedInit){
			try(var writer=writeCode()){
				writer.write(classVisibility.lower).write("visibility <init> function start function end");
			}catch(MalformedJorthException e){
				throw new RuntimeException(e);
			}
		}
		if(!addedClinit){
			try(var writer=writeCode()){
				writer.write(classVisibility.lower).write("visibility <clinit> static function start function end");
			}catch(MalformedJorthException e){
				throw new RuntimeException(e);
			}
		}
		
		currentClass.visitEnd();
		
		if(!rawTokens.isEmpty()){
			throw new IllegalStateException("Remaining data! "+rawTokens);
		}
		
		return currentClass.toByteArray();
	}
}
