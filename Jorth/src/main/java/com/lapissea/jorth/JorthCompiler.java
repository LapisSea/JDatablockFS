package com.lapissea.jorth;

import com.lapissea.jorth.lang.*;
import com.lapissea.util.*;
import com.lapissea.util.function.UnsafeBiConsumer;
import com.lapissea.util.function.UnsafeBiFunction;
import com.lapissea.util.function.UnsafeFunction;
import com.lapissea.util.function.UnsafeSupplier;
import org.objectweb.asm.ClassWriter;

import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ACC_SUPER;
import static org.objectweb.asm.Opcodes.V11;

public class JorthCompiler{
	
	private record Macro(String name, List<String> orderedArguments, Set<String> arguments, Token.Sequence.Writable data){}
	
	public record FunctArg(int index, String name, GenType type){}
	
	public record FunctionInfo(String name, String declaringClass, GenType returnType, List<GenType> arguments, CallType callType, boolean isStatic, boolean polymorphicSignature, Object defaultValue){
		private static CallType getCallType(Method method){
			if(Modifier.isStatic(method.getModifiers())){
				return CallType.STATIC;
			}else if(Modifier.isPrivate(method.getModifiers())){
				return CallType.SPECIAL;
			}else{
				return CallType.VIRTUAL;
			}
		}
		
		FunctionInfo(Method method){
			this(method.getName(),
			     method.getDeclaringClass().getName(),
			     new GenType(method.getGenericReturnType()),
			     Arrays.stream(method.getGenericParameterTypes()).map(GenType::new).toList(),
			     getCallType(method),
			     Modifier.isStatic(method.getModifiers()),
			     Arrays.stream(method.getAnnotations()).anyMatch(a->a.annotationType().getName().endsWith("PolymorphicSignature")),
			     method.getDefaultValue()
			);
			
		}
	}
	
	public record FieldInfo(String name, String declaringClass, GenType type, boolean isStatic){
		FieldInfo(Field field){
			this(field.getName(), field.getDeclaringClass().getName(), new GenType(field.getGenericType()), Modifier.isStatic(field.getModifiers()));
		}
	}
	
	public record Parm(String name, GenType type){
		Parm(Parameter parm){
			this(parm.getName(), new GenType(parm.getParameterizedType()));
		}
	}
	
	public record ConstructorInfo(String declaringClass, List<Parm> arguments){
		ConstructorInfo(Constructor<?> constructor){
			this(constructor.getDeclaringClass().getName(),
			     Arrays.stream(constructor.getParameters()).map(Parm::new).toList()
			);
		}
	}
	
	public record ClassInfo(String name, List<ClassInfo> parents, List<FunctionInfo> functions, List<ConstructorInfo> constructors, List<FieldInfo> fields){
		ClassInfo(Class<?> clazz){
			this(
				clazz.getName(),
				Stream.concat(Stream.of(clazz.getSuperclass()), Arrays.stream(clazz.getInterfaces()))
				      .filter(Objects::nonNull)
				      .map(ClassInfo::new)
				      .toList(),
				Arrays.stream(clazz.getDeclaredMethods()).map(FunctionInfo::new).toList(),
				Arrays.stream(clazz.getDeclaredConstructors()).map(ConstructorInfo::new).toList(),
				Arrays.stream(clazz.getDeclaredFields()).map(FieldInfo::new).toList()
			);
		}
		
		public boolean instanceOf(String className){
			if(className.equals(name)) return true;
			for(ClassInfo parent : parents){
				if(parent.instanceOf(className)) return true;
			}
			return false;
		}
	}
	
	public record AnnotationData(String className, Map<String, Object> args){}
	
	private final ClassLoader             classLoader;
	private final Token.Sequence.Writable rawTokens=new Token.Sequence.Writable();
	
	private int lastLine;
	
	private ClassInfo classInfo;
	
	private final List<AnnotationData> annotations=new ArrayList<>();
	private final Map<String, GenType> classFields=new HashMap<>();
	
	private ClassWriter currentClass;
	private JorthMethod currentMethod;
	private boolean     isStatic;
	private boolean     isFinal;
	
	private GenType classExtension=new GenType(Object.class.getName());
	
	private GenType returnType;
	
	private final Map<String, Macro> macros=new HashMap<>();
	private       Macro              currentMacro;
	
	private final LocalVariableStack methodArguments=new LocalVariableStack();
	
	private final List<JorthMethod.CodePoint> ifStack=new ArrayList<>();
	
	private final List<Token> startStack=new ArrayList<>();
	
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
		
		lastLine=Math.max(token.line, lastLine);
		
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
//		LogUtil.println(currentMethod,token.source);
		
		if(currentMethod!=null){
			
			if(token.isStringLiteral()){
				var str=token.getStringLiteralValue();
				currentMethod.loadString(str);
				return true;
			}
			
			if(token.isInteger()){
				currentMethod.loadInt(Integer.parseInt(token.source));
				return true;
			}
			
			if(token.isFloating()){
				throw new NotImplementedException();
			}
			
			interface DoMath{
				void run(String opName, UnsafeBiFunction<Types, Types, Boolean, MalformedJorthException> exec) throws MalformedJorthException;
			}
			
			DoMath math=(name, exec)->{
				var stack=currentMethod.getStack();
				if(stack.size()<2) throw new MalformedJorthException(name+" operation requires 2 operands on the stack");
				
				var a=stack.peek(0);
				var b=stack.peek(1);
				
				if(exec.apply(a.type(), b.type())) return;
				if(exec.apply(b.type(), a.type())) return;
				
				throw new MalformedJorthException("Unable to find match for "+name+" with "+a+", "+b);
			};
			
			switch(token.lower()){
				case "call" -> {
					doCall();
					return true;
				}
				case "cast" -> {
					doCast();
					return true;
				}
				case "+" -> {
					math.run("add", (a, b)->{
						if(a==b){
							switch(a){
								case BYTE, SHORT, INT -> currentMethod.integerAdd();
								case FLOAT -> currentMethod.floatAdd();
								case LONG -> currentMethod.longAdd();
								case DOUBLE -> currentMethod.doubleAdd();
								default -> throw new NotImplementedException(a+"");
							}
							return true;
						}
						return false;
					});
					return true;
				}
				case "-" -> {
					math.run("subtract", (a, b)->{
						if(a==b){
							switch(a){
								case BYTE, SHORT, INT -> currentMethod.integerSub();
								case FLOAT -> currentMethod.floatSub();
								case LONG -> currentMethod.longSub();
								case DOUBLE -> currentMethod.doubleSub();
								default -> throw new NotImplementedException(a+"");
							}
							return true;
						}
						return false;
					});
					return true;
				}
				case "/" -> {
					math.run("divide", (a, b)->{
						if(a==b){
							switch(a){
								case BYTE, SHORT, INT -> currentMethod.integerDiv();
								case FLOAT -> currentMethod.floatDiv();
								case LONG -> currentMethod.longDiv();
								case DOUBLE -> currentMethod.doubleDiv();
								default -> throw new NotImplementedException(a+"");
							}
							return true;
						}
						return false;
					});
					return true;
				}
				case "*" -> {
					math.run("multiply", (a, b)->{
						if(a==b){
							switch(a){
								case BYTE, SHORT, INT -> currentMethod.integerMul();
								case FLOAT -> currentMethod.floatMul();
								case LONG -> currentMethod.longMul();
								case DOUBLE -> currentMethod.doubleMul();
								default -> throw new NotImplementedException(a+"");
							}
							return true;
						}
						return false;
					});
					return true;
				}
				case "==" -> {
					var left =currentMethod.getStack().peek(0);
					var right=currentMethod.getStack().peek(1);
					
					var lbc=left.type();
					var rbc=right.type();
					
					try{
						if(lbc!=rbc){
							throw new NotImplementedException(left+" "+right+" comparison");
						}
						
						if(lbc==Types.INT){
							currentMethod.intIntEqualityToBool();
							return true;
						}
						if(lbc==Types.OBJECT){
							currentMethod.invoke(Objects.class.getMethod("equals", Object.class, Object.class));
							return true;
						}
					}catch(NoSuchMethodException e){
						throw new ShouldNeverHappenError(e);
					}
					
					throw new NotImplementedException(left+" "+right+" not supported yet");
				}
				case "concat" -> {
					try{
						currentMethod.swap();
						
						currentMethod.newObject(StringBuilder.class);
						currentMethod.dup();
						currentMethod.callInit(List.of());
						
						currentMethod.getStack().checkTop(List.of(GenType.STRING_BUILDER));
						
						UnsafeSupplier<Class<?>, MalformedJorthException> getTyp=()->{
							Class<?> cls;
							var      peek=currentMethod.getStack().peek();
							if(List.of(Types.BYTE, Types.CHAR, Types.SHORT).contains(peek.type())){
								currentMethod.growToInt();
								peek=currentMethod.getStack().peek();
							}
							if(peek.typeName().equals(String.class.getName())) cls=String.class;
							else cls=Objects.requireNonNull(peek.type().baseClass);
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
				case "set" -> {
					requireTokenCount(2);
					var name =pop();
					var owner=pop();
					
					switch(owner.source){
						case "this" -> {
							currentMethod.loadThis();
							currentMethod.swap();
							GenType type=classField(name);
							
							currentMethod.setFieldIns(classInfo.name, name.source, type);
						}
						case "<arg>" -> {
							throw new MalformedJorthException("can't set arguments! argument: "+name);
						}
						default -> {
							var ownerType=getClassInfo(owner.source);
							var field=ownerType.fields.stream()
							                          .filter(f->f.name.equals(name.source))
							                          .findAny()
							                          .orElseThrow(()->new MalformedJorthException(name+" does not exist in "+owner.source));
							
							currentMethod.setFieldIns(field);
						}
					}
					
					return true;
				}
				case "get" -> {
					requireTokenCount(2);
					var name =pop();
					var owner=pop();
//					LogUtil.println(name, owner);
					
					switch(owner.source){
						case "this" -> {
							currentMethod.loadThis();
							if(name.source.equals("this")){
								if(currentMethod.isStatic()) throw new MalformedJorthException("can't get this in static method");
							}else{
								var type=classFields.get(name.source);
								if(type==null) throw new MalformedJorthException(name+" does not exist in "+classInfo.name);
								
								currentMethod.getFieldIns(classInfo.name, name.source, type);
							}
						}
						case "<arg>" -> {
							var arg=methodArguments.get(name.source);
							if(arg.isEmpty()){
								throw new MalformedJorthException("argument "+name+" does not exist");
							}
							currentMethod.loadArgument(arg.get());
						}
						default -> {
							var ownerType=getClassInfo(owner.source);
							var field=ownerType.fields.stream()
							                          .filter(f->f.name.equals(name.source))
							                          .findAny()
							                          .orElseThrow(()->new MalformedJorthException(name+" does not exist in "+owner.source));
							
							currentMethod.getFieldIns(field);
						}
					}
					
					return true;
				}
				case "if" -> {
					if(currentMethod.getStack().peek().equals(new GenType(Boolean.class))){
						unbox();
					}
					
					var ifEnd=currentMethod.new CodePoint();
					
					currentMethod.jumpToIfFalse(ifEnd);
					ifStack.add(ifEnd);
					startStack.add(token);
					
					var ifStart=currentMethod.new CodePoint();
					ifStart.record();
					
					ifEnd.prev=ifStart;
					
					ifEnd.last=currentMethod.new CodePoint();
					return true;
				}
				case "else" -> {
					if(ifStack.isEmpty()) throw new MalformedJorthException("Else must follow an if block");
					
					var ifEnd=ifStack.remove(ifStack.size()-1);
					
					var first=ifEnd;
					while(first.prev!=null){
						first=first.prev;
					}
					
					currentMethod.jumpTo(ifEnd.last);
					
					ifEnd.plant();
					first.restoreStack();
					
					var elseEnd=currentMethod.new CodePoint();
					elseEnd.prev=ifEnd;
					elseEnd.last=ifEnd.last;
					
					ifStack.add(elseEnd);
					
					return true;
				}
				case "super" -> {
					if(!currentMethod.getName().equals("<init>")){
						throw new MalformedJorthException("super currently used only in init");
					}
					
					if(currentMethod.getStack().size()==0) throw new MalformedJorthException("super needs this on stack, add \"this this get\" before super");
					var args=new ArrayList<>(currentMethod.getStack().asList());
					if(!new GenType(classInfo.name).equals(args.remove(0))){
						throw new MalformedJorthException("super needs this on stack, add \"this this get\" before super");
					}
					
					var parent=getClassInfo(classExtension.typeName());
					
					Supplier<Stream<List<GenType>>> getConstrs=()->parent.constructors.stream().map(c->c.arguments.stream().map(Parm::type).toList());
					
					if(getConstrs.get().noneMatch(l->l.equals(args))){
						throw new MalformedJorthException("super received "+args+" but available constructors are:\n"+
						                                  getConstrs.get().map(Object::toString).collect(Collectors.joining("\n")));
					}
					
					
					currentMethod.invoke(CallType.SPECIAL, classExtension.typeName(), currentMethod.getName(), args, GenType.VOID, false);
					return true;
				}
				case "class" -> {
					var name=pop();
					var info=getClassInfo(name.source);
					currentMethod.classToStack(info.name);
					return true;
				}
			}
		}else{
			switch(token.source){
				case "static" -> {
					isStatic=true;
					return true;
				}
				case "final" -> {
					isFinal=true;
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
					var type=readGenericType();
					methodArguments.make(name.source, type);
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
				case "field" -> {
					requireTokenCount(2);
					var name=pop().source;
					var type=readGenericType();
					
					if(classFields.containsKey(name)){
						throw new MalformedJorthException("field "+name+" already exists!");
					}
					classFields.put(name, type);
					
					var descriptor=Utils.genericSignature(type.rawType());
					var signature =Utils.genericSignature(type);
					if(signature.equals(descriptor)) signature=null;
					
					var access=visibility.opCode;
					if(isStatic){
						access|=ACC_STATIC;
					}
					if(isFinal){
						access|=ACC_FINAL;
					}
					
					var fieldVisitor=currentClass.visitField(access, name, descriptor, signature, null);
					
					for(AnnotationData annotation : annotations){
						var annV=fieldVisitor.visitAnnotation(Utils.genericSignature(new GenType(annotation.className(), 0, List.of())), true);
						annotation.args.forEach((name1, value)->{
							if(value.getClass().isArray()){
								var arrAnn=annV.visitArray(name1);
								for(int i=0;i<Array.getLength(value);i++){
									arrAnn.visit(null, Array.get(value, i));
								}
								arrAnn.visitEnd();
								
							}else if(value instanceof Enum e){
								annV.visitEnum(name1, Utils.genericSignature(new GenType(e.getClass().getName())), e.name());
							}else{
								annV.visit(name1, value);
							}
						});
						annV.visitEnd();
					}
					annotations.clear();
					
					classInfo=new ClassInfo(classInfo.name, classInfo.parents, classInfo.functions, classInfo.constructors,
					                        Stream.concat(classInfo.fields.stream(), Stream.of(new FieldInfo(name, classInfo.name, type, isStatic))).toList());
					
					fieldVisitor.visitEnd();
					visibility=Visibility.PUBLIC;
					isStatic=false;
					isFinal=false;
					return true;
				}
				case "@" -> {
					requireTokenCount(1);
					var className=pop().source;
					
					if(annotations.stream().anyMatch(a->a.className.equals(className))){
						throw new MalformedJorthException("annotation "+className+" already exists!");
					}
					
					var info=getClassInfo(className);
					if(!info.instanceOf(Annotation.class.getName())) throw new MalformedJorthException(className+" is not an annotation type!");
					
					var map=info.functions.stream()
					                      .filter(f->!f.isStatic)
					                      .map(f->Map.entry(f, Optional.ofNullable(f.defaultValue())))
					                      .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
					
					boolean hasArgs=!rawTokens.isEmpty()&&rawTokens.peek().source.equals("}");
					if(hasArgs){
						class ArrToken extends Token{
							final String[] arr;
							public ArrToken(int line, String source, String[] arr){
								super(line, source);
								this.arr=arr;
							}
						}
						
						var sequence=readSequence(rawTokens, "{", "}");
						var builder =new LinkedList<Token>();
						while(!sequence.isEmpty()){
							var t=sequence.peek();
							if(t.source.equals("]")){
								var s  =readSequence(sequence, "[", "]");
								var str=s.parseStream(sq->sq.pop().source).toArray(String[]::new);
								builder.add(0, new ArrToken(t.line, "", str));
								continue;
							}
							builder.add(0, sequence.pop());
						}
						sequence=Token.Sequence.of(builder);
						
						UnsafeBiConsumer<FunctionInfo, Token, MalformedJorthException> addVal=(name, tok)->{
							map.put(name, Optional.of(switch(name.returnType.type()){
								case INT -> Integer.parseInt(tok.source);
								case OBJECT -> {
									if(tok instanceof ArrToken arTok){
										var arr=arTok.arr;
										
										if(name.returnType.equals(new GenType(String[].class))){
											yield arr;
										}
										
										throw new MalformedJorthException(name.returnType.toString());
									}
									if(name.returnType.equals(GenType.STRING)){
										yield tok.getStringLiteralValue();
									}
									var typInfo=getClassInfo(name.returnType.typeName());
									if(typInfo.instanceOf(Enum.class.getName())){
										Class<Enum> ec;
										try{
											ec=(Class<Enum>)Class.forName(typInfo.name);
										}catch(ClassNotFoundException e){
											throw new RuntimeException(e);
										}
										for(var e : ec.getEnumConstants()){
											if(e.name().equals(tok.source)){
												yield e;
											}
										}
										throw new MalformedJorthException(tok.source+" enum can not be found in "+typInfo.name);
									}
									throw new MalformedJorthException(name.returnType.toString());
								}
								default -> throw new NotImplementedException(name.returnType.type().toString());
							}));
						};
						
						if(sequence.getRemaining()==1&&map.keySet().stream().map(FunctionInfo::name).toList().equals(List.of("value"))){
							addVal.accept(map.keySet().stream().filter(e->e.name.equals("value")).findAny().orElseThrow(), sequence.pop());
						}else{
							for(var e : sequence.parseAll(e->Map.entry(e.pop().source, e.pop()))){
								var fun=info.functions.stream().filter(f->!f.isStatic).filter(f->f.name.equals(e.getKey()))
								                      .findAny().orElseThrow(()->new MalformedJorthException(e.getKey()+" does not exist in "+info.name));
								
								addVal.accept(fun, e.getValue());
							}
						}
					}
					
					if(map.values().stream().anyMatch(Optional::isEmpty)){
						throw new MalformedJorthException("Missing annotation arguments: "+
						                                  map.entrySet().stream().filter(e->e.getValue().isEmpty()).map(e->e.getKey().name).collect(Collectors.joining(", ")));
					}
					
					annotations.add(new AnnotationData(className, map.entrySet().stream().collect(Collectors.toMap(e->e.getKey().name(), e->e.getValue().orElseThrow()))));
					return true;
				}
			}
		}
		switch(token.lower()){
			case "define" -> {
				requireTokenCount(2);
				var tokenName=pop();
				var value    =pop();
				writer.addDefinition(tokenName.source, value.source);
				return true;
			}
			case "resolve" -> {
				requireTokenCount(1);
				var subject=pop();
				
				switch(subject.source){
					case "macro" -> {
						requireTokenCount(1);
						var name=pop();
						
						var macro=getMacro(name);
						
						UnsafeFunction<Token, Token.Sequence, MalformedJorthException> interpretArg=raw->{
							
							if(raw.isStringLiteral()){
								var w=new Token.Sequence.Writable();
								try(var writ=new JorthWriter(raw.line, (__, t)->w.write(t))){
									writ.write(raw.getStringLiteralValue());
								}
								return w;
							}
							
							return Token.Sequence.of(raw);
							
						};
						
						Map<String, Token.Sequence> macroArgumentMap=switch(peek().source){
							
							case "]" -> {
								var tokens=readSequence(rawTokens, "[", "]");
								
								
								Map<String, Token.Sequence> result=new HashMap<>();
								
								int index=0;
								while(!tokens.isEmpty()){
									Token raw=tokens.pop();
									var   seq=interpretArg.apply(raw);
									
									result.put(macro.orderedArguments.get(index), seq);
									index++;
								}
								
								yield Map.copyOf(result);
							}
							case "}" -> {
								var tokens=readSequence(rawTokens, "{", "}");
								yield tokens.parseStream(e->{
									var argName=e.pop().source;
									
									var raw  =e.pop();
									var value=interpretArg.apply(raw);
									
									if(!macro.arguments.contains(argName)) throw new MalformedJorthException(argName+" argument does not exist in macro "+macro.name());
									return Map.entry(argName, value);
								}).collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
							}
							default -> throw new MalformedJorthException("unexpected token: "+peek());
						};
						
						if(macroArgumentMap.size()!=macro.arguments.size()){
							var set=new HashSet<>(macro.arguments);
							set.removeAll(macroArgumentMap.keySet());
							
							throw new MalformedJorthException("Undefined arguments: "+set);
						}
						
						var builder=new LinkedList<Token>();
						macro.data.cloneTokens().flatMap(t->{
							if(t.isStringLiteral()){
								var str=t.getStringLiteralValue();
								for(var e : macroArgumentMap.entrySet()){
									var b=new LinkedList<String>();
									e.getValue().cloneTokens().map(Token::getSource).forEach(b::addFirst);
									str=str.replace(e.getKey(), String.join(" ", b));//TODO: escape ' to prevent string breaking
								}
								return Stream.of(new Token(t.line, "'"+str+"'"));
							}
							var arg=macroArgumentMap.get(t.source);
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
			case "start" -> {
				requireTokenCount(1);
				var subject=pop();
				
				startStack.add(subject);
				
				switch(subject.lower()){
					case "macro" -> {
						requireTokenCount(1);
						var name=pop();
						
						List<String> orderedArgs;
						try{
							orderedArgs=readSequence(rawTokens, "[", "]").parseAll(r->r.pop().source);
						}catch(IllegalArgumentException e){
							throw new IllegalArgumentException("Bad arguments: "+this.methodArguments, e);
						}
						
						currentMacro=new Macro(name.source, orderedArgs, Set.copyOf(orderedArgs), new Token.Sequence.Writable());
						macros.put(currentMacro.name, currentMacro);
					}
					case "class" -> {
						if(currentClass!=null) throw new MalformedJorthException("Class "+currentClass+" already started!");
						requireTokenCount(1);
						var className=pop().source;
						classInfo=new ClassInfo(className, Stream.concat(Stream.of(classExtension), classInterfaces.stream()).map(g->{
							try{
								return getClassInfo(g.typeName());
							}catch(MalformedJorthException e){
								throw UtilL.uncheckedThrow(e);
							}
						}).toList(), List.of(), List.of(), List.of());
						
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
						
						currentClass.visit(V11, classVisibility.opCode|ACC_SUPER|ACC_FINAL, nam, signature.toString(), ext, interfaces);
						
						visibility=Visibility.PUBLIC;
						
					}
					case "function" -> {
						requireTokenCount(1);
						var functionName=pop();
						
						var staticOo=isStatic?ACC_STATIC:0;
						
						String genericReturnStr=returnType!=null?Utils.genericSignature(returnType):"V";
						
						Supplier<Stream<GenType>> argTypes=
							()->methodArguments.stream()
							                   .sorted(Comparator.comparingInt(LocalVariableStack.Variable::accessIndex))
							                   .map(LocalVariableStack.Variable::type);
						
						var genericArgs=argTypes.get()
						                        .map(Utils::genericSignature)
						                        .collect(Collectors.joining());
						
						String returnStr=returnType!=null?Utils.genericSignature(returnType.rawType()):"V";
						
						var args=argTypes.get()
						                 .map(GenType::rawType)
						                 .map(Utils::genericSignature)
						                 .collect(Collectors.joining());
						
						
						String descriptor="("+args+")"+returnStr, signature="("+genericArgs+")"+genericReturnStr;
						if(signature.equals(descriptor)) signature=null;
						
						var dest=currentClass.visitMethod(visibility.opCode+staticOo, functionName.source, descriptor, signature, null);
						
						for(AnnotationData annotation : annotations){
							var annV=dest.visitAnnotation(Utils.genericSignature(new GenType(annotation.className(), 0, List.of())), true);
							annotation.args.forEach(annV::visit);
							annV.visitEnd();
						}
						annotations.clear();
						
						var info=new FunctionInfo(functionName.source, classInfo.name, returnType==null?GenType.VOID:returnType, methodArguments.stream().map(LocalVariableStack.Variable::type).toList(), isStatic?CallType.STATIC:CallType.VIRTUAL, isStatic, false, null);
						classInfo=new ClassInfo(classInfo.name, classInfo.parents, Stream.concat(classInfo.functions.stream(), Stream.of(info)).toList(), classInfo.constructors, classInfo.fields);
						
						currentMethod=new JorthMethod(this, dest, functionName.source, classInfo.name, returnType, isStatic);
						currentMethod.start();
						
						isStatic=false;
						
						if("<clinit>".equals(functionName.source)){
							addedClinit=true;
						}
						if("<init>".equals(functionName.source)){
							addedInit=true;
						}
					}
					default -> throw new MalformedJorthException("Unknown subject "+subject+". Can not start it");
				}
				
				return true;
			}
			case "end" -> {
				var subject=startStack.remove(startStack.size()-1);
				
				requireEmptyWords();
				
				switch(subject.lower()){
					case "function" -> {
						try{
							currentMethod.returnOp();
							
							currentMethod.end();
							currentMethod=null;
							returnType=null;
							methodArguments.clear();
							
						}catch(Throwable e){
							throw new MalformedJorthException("Failed to end function ", e);
						}
					}
					case "if" -> {
						var lastEnd=ifStack.remove(ifStack.size()-1);
						
						lastEnd.plant();
						lastEnd.last.plant();
						
						var point=lastEnd;
						while(point.prev!=null){
							LogUtil.println(point.getStackState());
							
							if(!point.getStackState().equals(lastEnd.getStackState())){
								throw new MalformedJorthException("Stack mismatch \n"+point.getStackState()+"\n"+lastEnd.getStackState());
							}
							
							point=point.prev;
						}
						
					}
					
					default -> throw new MalformedJorthException("Unknown subject "+subject+". Can not end it");
				}
				return true;
			}
			case "???" -> {
				if(currentMethod==null) throw new MalformedJorthException("Token ??? can only be used in a method body.");
				throw new MalformedJorthException("Debug token '???' at line "+token.line+" encountered. Current type stack:\n"+currentMethod.getStack());
			}
		}
		
		rawTokens.write(token);
		return false;
	}
	
	private void doCast() throws MalformedJorthException{
		requireTokenCount(1);
		var castType=readGenericType();
		
		var type=currentMethod.getStack().peek();
		if(castType.equals(type)) return;
		
		switch(type.type()){
			case INT -> {
				switch(castType.type()){
					case BYTE -> currentMethod.intToByte();
					case SHORT -> currentMethod.intToShort();
					case LONG -> currentMethod.intToLong();
					case FLOAT -> currentMethod.intToFloat();
					case DOUBLE -> currentMethod.intToDouble();
					default -> throw new NotImplementedException(castType+"");
				}
			}
			case OBJECT -> {
				switch(castType.type()){
					case OBJECT -> {
						currentMethod.castTo(castType);
					}
					default -> throw new NotImplementedException(castType+"");
				}
			}
			default -> throw new NotImplementedException(castType+"");
		}

//		LogUtil.println(type+" to "+castType);
	}
	
	private void doCall() throws MalformedJorthException{
		boolean staticCall;
		requireTokenCount(2);
		var argCountStr=pop();
		if(argCountStr.source.equals("static")){
			staticCall=true;
			argCountStr=pop();
		}else staticCall=false;
		var functionNameToken=pop();
		var functionName     =functionNameToken.source.startsWith("!")?functionNameToken.source.substring(1):functionNameToken.source;
		
		var argStr=argCountStr.source;
		var validMsg="""
			Needed argument count in (number) format. Valid call example:\s
			this mathField get
			1 2
			add2Numbers (2) call""";
		if(!argStr.startsWith("(")) throw new MalformedJorthException(validMsg);
		if(!argStr.endsWith(")")) throw new MalformedJorthException(validMsg);
		int argCount;
		try{
			argCount=Integer.parseInt(argStr.substring(1, argStr.length()-1));
		}catch(NumberFormatException e){
			throw new MalformedJorthException(validMsg, e);
		}
		
		var stack=currentMethod.getStack();
		
		List<GenType> argTypes=new ArrayList<>(argCount);
		for(int i=0;i<argCount;i++){
			argTypes.add(stack.peek(i));
		}
		
		ClassInfo callerInfo;
		if(staticCall){
			var className=pop();
			
			callerInfo=getClassInfo(className.source);
		}else{
			var callerType=stack.peek(argCount);
			
			callerInfo=getClassInfo(callerType.typeName());
		}
		
		Function<Predicate<FunctionInfo>, Optional<FunctionInfo>> make=
			pred->callerInfo.functions().stream()
			                .filter(f->(f.callType==CallType.STATIC)==staticCall)
			                .filter(f->f.name.equals(functionName))
			                .filter(f->f.arguments().size()==argCount)
			                .filter(pred)
			                .findAny();
		
		var functO=make.apply(f->{
			for(int i=0;i<f.arguments().size();i++){
				var a=f.arguments().get(i);
				var b=currentMethod.getStack().peek(i);
				if(!a.equals(b)){
					return false;
				}
			}
			return true;
		});
		if(functO.isEmpty()){
			functO=make.apply(f->{
				for(int i=0;i<f.arguments().size();i++){
					try{
						var a=f.arguments().get(i);
						var b=currentMethod.getStack().peek(i);
						if(!b.instanceOf(this, a)){
							return false;
						}
					}catch(MalformedJorthException e){
						throw UtilL.uncheckedThrow(e);
					}
				}
				return true;
			});
		}
		if(functO.isEmpty()){
			functO=callerInfo.functions().stream()
			                 .filter(f->(f.callType==CallType.STATIC)==staticCall)
			                 .filter(f->f.name.equals(functionName))
			                 .filter(f->f.polymorphicSignature)
			                 .findAny();
		}
		if(functO.isEmpty()) throw new MalformedJorthException("No function "+functionName+" with "+argCount+" arguments");
		var funct=functO.get();
		if(funct.polymorphicSignature){
			var args=stack.asList().subList(stack.size()-argCount, stack.size());
			currentMethod.invoke(funct.callType, funct.declaringClass, funct.name, args, funct.returnType(), false);
		}else{
			currentMethod.invoke(funct.callType, funct.declaringClass, funct.name, funct.arguments, funct.returnType(), false);
		}
	}
	
	private void unbox(){
		throw new NotImplementedException();
	}
	
	
	private void nullSafeStringLength() throws MalformedJorthException{
		currentMethod.getStack().checkTop(GenType.STRING);
		currentMethod.dup();
		
		currentMethod.nullBlock(()->{
			currentMethod.pop();
			currentMethod.loadInt(4);
		}, ()->{
			try{
				currentMethod.invoke(String.class.getMethod("length"));
			}catch(NoSuchMethodException e){
				throw new ShouldNeverHappenError("no.");
			}
		});
	}
	
	private GenType classField(Token name) throws MalformedJorthException{
		var type=classFields.get(name.source);
		if(type==null) throw new MalformedJorthException(name+" does not exist in "+classInfo.name);
		return type;
	}
	private Macro getMacro(Token name) throws MalformedJorthException{
		var type=macros.get(name.source);
		if(type==null) throw new MalformedJorthException("macro "+name+" does not exist");
		return type;
	}
	
	@NotNull
	private Token.Sequence.Sized readSequence(Token.Sequence tokens, String open, String close) throws MalformedJorthException{
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
		
		var arrayDimensions=0;
		while(!tokens.isEmpty()&&tokens.peek().source.equals("array")){
			tokens.pop();
			arrayDimensions++;
		}
		
		Token.Sequence argTokens=readSequence(tokens, "[", "]");
		List<GenType>  args     =argTokens.parseAll(this::readGenericType);
		
		return new GenType(typeName.source, arrayDimensions, List.copyOf(args));
	}
	
	private void requireTokenCount(int minWordCount) throws MalformedJorthException{
		rawTokens.requireCount(minWordCount);
	}
	
	public JorthWriter writeCode(){
		return new JorthWriter(lastLine, this::consumeRawToken);
	}
	
	
	public byte[] classBytecode() throws MalformedJorthException{return classBytecode(false);}
	public byte[] classBytecode(boolean printBytecode) throws MalformedJorthException{
		
		if(!addedInit){
			try(var writer=writeCode()){
				var parent=getClassInfo(classExtension.typeName());
				
				writer.write(classVisibility.lower).write("visibility");
				if(parent.constructors.size()==1){
					var ay=parent.constructors.get(0);
					
					for(var argument : ay.arguments){
						writer.write(argument.type.asJorthString()).write(argument.name).write("arg");
					}
					
					writer.write(
						"""
							<init> function start
								this this get
							""");
					
					for(var argument : ay.arguments){
						writer.write("<arg>").write(argument.name).write("get");
					}
					writer.write("super end");
				}else{
					writer.write(
						"""
							<init> function start
								this this get
								super
							end
							""");
				}
			}catch(MalformedJorthException e){
				throw new RuntimeException(e);
			}
		}
//		if(!addedClinit){
//			try(var writer=writeCode()){
//				writer.write(classVisibility.lower).write("visibility <clinit> static function start end");
//			}catch(MalformedJorthException e){
//				throw new RuntimeException(e);
//			}
//		}
		
		currentClass.visitEnd();
		
		requireEmptyWords();
		
		var ba=currentClass.toByteArray();
		if(printBytecode){
			BytecodeUtils.printClass(ba);
		}
		return ba;
	}
	
	private void requireEmptyWords() throws MalformedJorthException{
		if(!rawTokens.isEmpty()){
			throw new MalformedJorthException("Remaining words! "+rawTokens);
		}
	}
	
	public ClassInfo getClassInfo(String name) throws MalformedJorthException{
		if(name.contains("/")){
			throw new IllegalArgumentException(name);
		}
		if(classInfo!=null&&classInfo.name.equals(name)){
			return classInfo;
		}
		
		Class<?> clazz;
		try{
			clazz=Class.forName(name, false, classLoader);
		}catch(ClassNotFoundException e){
			throw new MalformedJorthException(e);
		}
		
		return new ClassInfo(clazz);
	}
}
