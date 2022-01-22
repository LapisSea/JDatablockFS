package com.lapissea.jorth;

import com.lapissea.jorth.lang.*;
import com.lapissea.util.LogUtil;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.NotNull;
import com.lapissea.util.ShouldNeverHappenError;
import org.objectweb.asm.ClassWriter;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ACC_SUPER;
import static org.objectweb.asm.Opcodes.V11;

public class JorthCompiler{
	
	private record Macro(String name, Set<String> arguments, Token.Sequence.Writable data){}
	
	public record FunctArg(int index, String name, GenType type){}
	
	public record ClassInfo(String name, List<ClassInfo> parents){
		
		private static final ClassInfo OBJECT=new ClassInfo(Object.class.getName(), List.of());
		
		public static ClassInfo fromClass(Class<?> clazz){
			if(clazz.equals(Object.class)) return OBJECT;
			return new ClassInfo(clazz.getName(), Stream.concat(Stream.of(clazz.getSuperclass()), Arrays.stream(clazz.getInterfaces())).filter(Objects::nonNull).map(ClassInfo::fromClass).toList());
		}
		
		public boolean instanceOf(String className){
			if(className.equals(name)) return true;
			for(ClassInfo parent : parents){
				if(parent.instanceOf(className)) return true;
			}
			return false;
		}
	}
	
	private final ClassLoader             classLoader;
	private final Token.Sequence.Writable rawTokens=new Token.Sequence.Writable();
	
	private int lastLine;
	
	private       String               className;
	private final Map<String, GenType> classFields=new HashMap<>();
	
	private ClassWriter currentClass;
	private JorthMethod currentMethod;
	private boolean     isStatic;
	
	private GenType classExtension=new GenType(Object.class.getName());
	
	private GenType returnType;
	
	private final Map<String, Macro> macros=new HashMap<>();
	private       Macro              currentMacro;
	
	private final Map<String, FunctArg> arguments=new HashMap<>();
	
	private final List<GenType> classInterfaces=new ArrayList<>();
	private       Visibility    classVisibility;
	private       boolean       addedInit;
	private       boolean       addedClinit;
	private       Visibility    visibility     =Visibility.PUBLIC;
	
	public JorthCompiler(ClassLoader classLoader){
		this.classLoader=classLoader;
	}
	
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
			var str=token.getStringLiteralValue();
			currentMethod.loadString(str);
			return true;
		}
		
		switch(token.lower()){
			case "static" -> {
				isStatic=true;
				return true;
			}
			case "concat" -> {
				try{
					var stack=currentMethod.getStack();
					if(stack.peek().equals(GenType.STRING)&&stack.peek(1).equals(GenType.STRING)){
						
						currentMethod.dupAB();
						currentMethod.invoke(String.class.getMethod("length"));
						currentMethod.swap();
						currentMethod.invoke(String.class.getMethod("length"));
						currentMethod.add();
						
						currentMethod.newObject(StringBuilder.class);
						currentMethod.dupTo1Below();
						currentMethod.swap();
						currentMethod.callInit(List.of(new GenType(int.class.getName())));
					}else{
						currentMethod.newObject(StringBuilder.class, List.of());
					}
					
					Supplier<Class<?>> getTyp=()->{
						Class<?> cls;
						var      peek=stack.peek();
						if(peek.typeName().equals(String.class.getName())) cls=String.class;
						else cls=Objects.requireNonNull(stack.peek().type().baseClass);
						return cls;
					};
					
					currentMethod.swap();
					currentMethod.invoke(StringBuilder.class.getMethod("append", getTyp.get()));
					
					currentMethod.swap();
					currentMethod.invoke(StringBuilder.class.getMethod("append", getTyp.get()));
					
					currentMethod.invoke(StringBuilder.class.getMethod("toString"));
					
					return true;
				}catch(NoSuchMethodException e){
					throw new ShouldNeverHappenError(e);
				}
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
						currentMethod.loadThis();
						currentMethod.swap();
						GenType type=classField(name);
						
						currentMethod.setFieldIns(className, name.source, type);
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
						currentMethod.loadThis();
						
						var type=classFields.get(name.source);
						if(type==null) throw new MalformedJorthException(name+" does not exist in "+className);
						
						currentMethod.getFieldIns(className, name.source, type);
					}
					case "<arg>" -> {
						var arg=arguments.values()
						                 .stream()
						                 .filter(a->a.name.equals(name.source))
						                 .findAny();
						if(arg.isEmpty()){
							throw new MalformedJorthException("argument "+name+" does not exist");
						}
						currentMethod.loadArgument(arg.get());
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
				
				currentClass.visitField(visibility.opCode, name, Utils.genericSignature(new GenType(type.typeName())), Utils.genericSignature(type), null);
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
						signature.append(Utils.genericSignature(classExtension));
						for(GenType sig : classInterfaces){
							signature.append(Utils.genericSignature(sig));
						}
						
						var interfaces=classInterfaces.stream().map(sig->Utils.undotify(sig.typeName())).toArray(String[]::new);
						if(interfaces.length==0) interfaces=null;
						
						var nam=Utils.undotify(className);
						var ext=Utils.undotify(classExtension.typeName());
						
						currentClass.visit(V11, classVisibility.opCode+ACC_SUPER, nam, signature.toString(), ext, interfaces);
						
						visibility=Visibility.PUBLIC;
						
					}
					case "function" -> {
						requireTokenCount(1);
						var functionName=pop();
						
						String returnStr;
						if(returnType!=null) returnStr=Utils.genericSignature(returnType);
						else returnStr="V";
						
						var staticOo=isStatic?ACC_STATIC:0;
						
						var args=arguments.values()
						                  .stream()
						                  .sorted(Comparator.comparingInt(FunctArg::index))
						                  .map(FunctArg::type)
						                  .map(Utils::genericSignature)
						                  .collect(Collectors.joining());
						
						
						var dest=currentClass.visitMethod(visibility.opCode+staticOo, functionName.source, "("+args+")"+returnStr, null, null);
						
						currentMethod=new JorthMethod(dest, className, returnType, isStatic);
						currentMethod.start();
						
						isStatic=false;
						
						if("<clinit>".equals(functionName.source)){
							addedClinit=true;
						}
						if("<init>".equals(functionName.source)){
							addedInit=true;
							currentMethod.loadThis();
							currentMethod.invoke(CallType.SPECIAL, Utils.undotify(classExtension.typeName()), functionName.source, List.of(), GenType.VOID, false);
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
						currentMethod.returnOp();
						
						currentMethod.end();
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
	
	private void requireTokenCount(int minWordCount) throws MalformedJorthException{
		rawTokens.requireCount(minWordCount);
	}
	
	public JorthWriter writeCode(){
		return new JorthWriter(lastLine, this::consumeRawToken);
	}
	
	
	public byte[] classBytecode(){
		
//		if(!addedInit){
//			try(var writer=writeCode()){
//				writer.write(classVisibility.lower).write("visibility <init> function start function end");
//			}catch(MalformedJorthException e){
//				throw new RuntimeException(e);
//			}
//		}
//		if(!addedClinit){
//			try(var writer=writeCode()){
//				writer.write(classVisibility.lower).write("visibility <clinit> static function start function end");
//			}catch(MalformedJorthException e){
//				throw new RuntimeException(e);
//			}
//		}
		
		currentClass.visitEnd();
		
		if(!rawTokens.isEmpty()){
			throw new IllegalStateException("Remaining data! "+rawTokens);
	
	public ClassInfo getClassInfo(String name) throws MalformedJorthException{
		if(name.contains("/")){
			throw new IllegalArgumentException(name);
		}
		if(className.equals(name)){
			throw new NotImplementedException();
		}
		
		Class<?> clazz;
		try{
			clazz=Class.forName(name, false, classLoader);
		}catch(ClassNotFoundException e){
			throw new MalformedJorthException(e);
		}
		
		return ClassInfo.fromClass(clazz);
	}
}
