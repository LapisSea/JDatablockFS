package com.lapissea.jorth;

import com.lapissea.jorth.lang.GenType;
import com.lapissea.jorth.lang.Token;
import com.lapissea.jorth.lang.Visibility;
import com.lapissea.util.LogUtil;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.NotNull;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.objectweb.asm.Opcodes.*;

public class JorthCompiler{
	
	private record Macro(String name, Set<String> arguments, Token.Sequence.Writable data){}
	
	private record FunctArg(int index, String name, GenType type){}
	
	private final Token.Sequence.Writable rawTokens=new Token.Sequence.Writable();
	
	private int lastLine;
	
	private String               className;
	private Map<String, GenType> classFields=new HashMap<>();
	
	private ClassWriter   currentClass;
	private MethodVisitor currentMethod;
	private boolean       isStatic;
	
	private GenType classExtension=new GenType(Object.class.getName(), List.of());
	
	private GenType returnType;
	
	private Map<String, Macro> macros=new HashMap<>();
	private Macro              currentMacro;
	
	private Map<String, FunctArg> arguments=new HashMap<>();
	
	private List<GenType> classInterfaces=new ArrayList<>();
	private Visibility    classVisibility;
	private boolean       addedInit;
	private boolean       addedClinit;
	private Visibility    visibility     =Visibility.PUBLIC;
	
	private Token peek() throws MalformedJorthException{
		return rawTokens.peek();
	}
	private Token pop() throws MalformedJorthException{
		return rawTokens.pop();
	}
	
	private void consumeRawToken(JorthWriter writer, Token token) throws MalformedJorthException{
		
		if(currentMacro!=null){
			var d=currentMacro.data;
			
			if(token.source.equals("end")){
				var subject=d.peek();
				if(subject.source.equals("macro")){
					d.pop();
					currentMacro=null;
					return;
				}
			}
			
			d.write(token);
			return;
		}
		
//		var deb=rawTokens+" "+token;
		if(consume(writer, token)){
//			LogUtil.println(deb);
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
			case "concat" -> {
				if(true) throw new NotImplementedException("concat not implemented");
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
						GenType type=classField(name);
						
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
						if(type==null) throw new MalformedJorthException(name+" does not exist in "+className);
						
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
			case "resolve" -> {
				requireTokenCount(1);
				var subject=pop();
				
				switch(subject.source){
					case "macro" -> {
						requireTokenCount(1);
						var name=pop();
						
						var macro =getMacro(name);
						var tokens=readSequence(rawTokens, "{", "}");
						
						var m=tokens.parseStream(e->{
							var argName=e.pop().source;
							var raw    =e.pop();
							
							Token.Sequence value;
							if(raw.isStringLiteral()){
								var w=new Token.Sequence.Writable();
								try(var writ=new JorthWriter(raw.line, (__, t)->w.write(t))){
									writ.write(raw.getStringLiteralValue());
								}
								value=w;
							}else{
								value=Token.Sequence.of(raw);
							}
							
							return Map.entry(argName, value);
						}).collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
						
						var builder=new LinkedList<Token>();
						macro.data.cloneTokens().flatMap(t->{
							if(t.isStringLiteral()){
								var str=t.getStringLiteralValue();
								for(var e : m.entrySet()){
									var b=new LinkedList<String>();
									e.getValue().cloneTokens().map(Token::getSource).forEach(b::addFirst);
									str=str.replace(e.getKey(), String.join(" ", b));//TODO: escape ' to prevent string breaking
								}
								return Stream.of(new Token(t.line, "'"+str+"'"));
							}
							var arg=m.get(t.source);
							if(arg!=null){
								var line=t.line;
								return arg.cloneTokens().map(Token::getSource).map(src->new Token(line, src));
							}
							return Stream.of(t);
						}).forEach(builder::addFirst);
						
						for(Token t : builder){
							consumeRawToken(null, t);
						}
					}
					case "nothing" -> {
						LogUtil.println("congrats you just resolved nothing. Think about your life choices.");
					}
					default -> throw new MalformedJorthException("unknown resolve subject: "+subject);
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
					case "macro" -> {
						requireTokenCount(1);
						var name=pop();
						
						Set<String> arguments;
						try{
							arguments=Set.copyOf(readSequence(rawTokens, "[", "]").parseAll(r->r.pop().source));
						}catch(IllegalArgumentException e){
							throw new IllegalArgumentException("Bad arguments: "+this.arguments, e);
						}
						currentMacro=new Macro(name.source, arguments, new Token.Sequence.Writable());
						macros.put(currentMacro.name, currentMacro);
					}
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
						isStatic=false;
						
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
		
		rawTokens.write(token);
		return false;
	}
	
	private GenType classField(Token name) throws MalformedJorthException{
		var type=classFields.get(name.source);
		if(type==null) throw new MalformedJorthException(name+" does not exist in "+className);
		return type;
	}
	private Macro getMacro(Token name) throws MalformedJorthException{
		var type=macros.get(name.source);
		if(type==null) throw new MalformedJorthException("macro "+name+" does not exist");
		return type;
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
	
	@NotNull
	private Token.Sequence readSequence(Token.Sequence tokens, String open, String close) throws MalformedJorthException{
		if(tokens.isEmpty()) return Token.Sequence.EMPTY;
		
		var builder=new LinkedList<Token>();
		
		var first=tokens.peek();
		if(!close.equals(first.source)) return Token.Sequence.EMPTY;
		
		tokens.pop();
		
		int depth=1;
		
		while(true){
			var token=tokens.pop();
			if(token.source.equals(close)){
				depth++;
			}else if(token.source.equals(open)){
				depth--;
			}
			if(depth==0) return Token.Sequence.of(builder);
			builder.addFirst(token);
		}
		
	}
	
	private GenType readGenericType() throws MalformedJorthException{
		return readGenericType(rawTokens);
	}
	
	private GenType readGenericType(Token.Sequence tokens) throws MalformedJorthException{
		tokens.requireCount(1);
		var typeName=tokens.pop();
		
		Token.Sequence argTokens=readSequence(tokens, "[", "]");
		List<GenType>  args     =argTokens.parseAll(this::readGenericType);
		
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
		rawTokens.requireCount(minWordCount);
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
