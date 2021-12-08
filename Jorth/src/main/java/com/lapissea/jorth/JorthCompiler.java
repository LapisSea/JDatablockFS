package com.lapissea.jorth;

import com.lapissea.util.LogUtil;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

import static org.objectweb.asm.Opcodes.*;

public class JorthCompiler{
	
	private record GenericSig(String type, List<GenericSig> args){}
	
	private final JorthWriter stream=new JorthWriter(this::consumeRawToken);
	
	private final Deque<String> rawTokens=new LinkedList<>();
	
	private String className;
	
	private ClassWriter   currentClass;
	private MethodVisitor currentMethod;
	
	private GenericSig       classExtension =new GenericSig(Object.class.getName(), List.of());
	private GenericSig       returnType;
	private List<GenericSig> classInterfaces=new ArrayList<>();
	private int              visibility     =ACC_PUBLIC;
	
	private void consumeRawToken(String tokenStr) throws MalformedJorthException{
		if(tokenStr.startsWith("'")&&tokenStr.endsWith("'")){
			var str=tokenStr.substring(1, tokenStr.length()-1);
			currentMethod.visitLdcInsn(str);
			return;
		}
		
		LogUtil.println(tokenStr+" "+rawTokens);
		String lowerToken=tokenStr.toLowerCase();
		switch(lowerToken){
			case "define" -> {
				requireTokenCount(2);
				var tokenName=rawTokens.pop();
				var value    =rawTokens.pop();
				stream.addDefinition(tokenName, value);
				return;
			}
			case "extends" -> {
				classExtension=readGenericType();
				return;
			}
			case "returns" -> {
				returnType=readGenericType();
				return;
			}
			case "implements" -> {
				classInterfaces.add(readGenericType());
				return;
			}
			case "visibility" -> {
				requireTokenCount(1);
				var visibilityStr=rawTokens.pop();
				this.visibility=switch(visibilityStr.toLowerCase()){
					case "public" -> ACC_PUBLIC;
					case "private" -> ACC_PRIVATE;
					case "protected" -> ACC_PROTECTED;
					default -> throw new MalformedJorthException("Unknown visibility "+visibilityStr);
				};
				return;
			}
			case "end" -> {
				requireTokenCount(1);
				var subject=rawTokens.pop();
				switch(subject.toLowerCase()){
					case "function" -> {
						if(returnType!=null){
							currentMethod.visitInsn(ARETURN);
						}
						
						currentMethod.visitMaxs(0, 0);
						currentMethod.visitEnd();
						currentMethod=null;
						returnType=null;
					}
					
					default -> throw new MalformedJorthException("Unknown subject "+subject+". Can not end it");
				}
				return;
			}
			case "start" -> {
				requireTokenCount(1);
				var subject=rawTokens.pop();
				
				
				switch(subject.toLowerCase()){
					case "function" -> {
						requireTokenCount(1);
						var functionName=rawTokens.pop();
						
						String returnStr;
						if(returnType!=null) returnStr=genericSignature(returnType);
						else returnStr="V";
						
						currentMethod=currentClass.visitMethod(visibility, functionName, "()"+returnStr, null, null);
						currentMethod.visitCode();
						
						if("<init>".equals(functionName)){
							currentMethod.visitVarInsn(ALOAD, 0);
							currentMethod.visitMethodInsn(INVOKESPECIAL, undotify(classExtension.type), functionName, "()V", false);
							currentMethod.visitInsn(RETURN);
						}
					}
					case "class" -> {
						if(currentClass!=null) throw new MalformedJorthException("Class "+currentClass+" already started!");
						requireTokenCount(1);
						className=rawTokens.pop();
						
						currentClass=new ClassWriter(ClassWriter.COMPUTE_MAXS|ClassWriter.COMPUTE_FRAMES);
						
						var signature=new StringBuilder();
						signature.append(genericSignature(classExtension));
						for(GenericSig sig : classInterfaces){
							signature.append(genericSignature(sig));
						}
						
						var interfaces=classInterfaces.stream().map(sig->undotify(sig.type)).toArray(String[]::new);
						if(interfaces.length==0) interfaces=null;
						
						var nam=undotify(className);
						var ext=undotify(classExtension.type);
						
						LogUtil.println(visibility+ACC_FINAL,
						                nam,
						                signature.toString(),
						                ext,
						                interfaces
						);
						
						currentClass.visit(V11,
						                   visibility+ACC_SUPER,
						                   nam,
						                   signature.toString(),
						                   ext,
						                   interfaces);
						
						visibility=ACC_PUBLIC;
						
						LogUtil.println("starting", subject);
						
					}
					default -> throw new MalformedJorthException("Unknown subject "+subject+". Can not start it");
				}
				
				return;
			}
		}
		rawTokens.push(tokenStr);
	}
	
	private GenericSig readGenericType() throws MalformedJorthException{
		requireTokenCount(1);
		var              typeName=rawTokens.pop();
		List<GenericSig> args    =new ArrayList<>(4);
		
		if(!rawTokens.isEmpty()){
			var first=rawTokens.peekFirst();
			if("]".equals(first)){
				rawTokens.pop();
				
				while(true){
					var token=rawTokens.peekFirst();
					if("[".equals(token)){
						rawTokens.removeFirst();
						break;
					}
					var subType=readGenericType();
					args.add(subType);
				}
			}
		}
		
		return new GenericSig(typeName, List.copyOf(args));
	}
	
	private String genericSignature(GenericSig sig){
		String argStr;
		if(sig.args.isEmpty()) argStr="";
		else{
			argStr=sig.args.stream().map(this::genericSignature).collect(Collectors.joining("", "<", ">"));
		}
		return "L"+undotify(sig.type)+argStr+";";
	}
	
	private String undotify(String className){
		return className.replace('.', '/');
	}
	
	private void requireTokenCount(int minWordCount) throws MalformedJorthException{
		if(rawTokens.size()<minWordCount) throw new MalformedJorthException("token requires at least "+minWordCount+" words!");
	}
	
	public JorthWriter writeCode(){
		return stream;
	}
	
	
	public ByteBuffer classBytecode(){
		currentClass.visitEnd();
		if(!rawTokens.isEmpty()){
			LogUtil.println("Remaining data!");
			LogUtil.println(rawTokens);
			throw new IllegalStateException();
		}
		return ByteBuffer.wrap(currentClass.toByteArray());
	}
}
