package com.lapissea.jorth;

import com.lapissea.jorth.exceptions.EndOfCode;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import com.lapissea.jorth.lang.CodeDestination;
import com.lapissea.jorth.lang.Endable;
import com.lapissea.jorth.lang.Keyword;
import com.lapissea.jorth.lang.Preload;
import com.lapissea.jorth.lang.Token;
import com.lapissea.jorth.lang.TokenSource;
import com.lapissea.jorth.lang.Tokenizer;
import com.lapissea.jorth.lang.type.Access;
import com.lapissea.jorth.lang.type.AnnGen;
import com.lapissea.jorth.lang.type.BaseType;
import com.lapissea.jorth.lang.type.ClassGen;
import com.lapissea.jorth.lang.type.ClassInfo;
import com.lapissea.jorth.lang.type.ClassType;
import com.lapissea.jorth.lang.type.FunctionGen;
import com.lapissea.jorth.lang.type.GenericType;
import com.lapissea.jorth.lang.type.JType;
import com.lapissea.jorth.lang.type.KeyedEnum;
import com.lapissea.jorth.lang.type.Operation;
import com.lapissea.jorth.lang.type.TypeSource;
import com.lapissea.jorth.lang.type.Visibility;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.function.UnsafeConsumer;

import java.lang.reflect.Array;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.lapissea.util.ConsoleColors.*;

public final class Jorth extends CodeDestination{
	
	public static byte[] generateClass(ClassLoader classLoader, String className, UnsafeConsumer<CodeStream, MalformedJorth> generator) throws MalformedJorth{
		return generateClass(classLoader, className, generator, null);
	}
	public static byte[] generateClass(ClassLoader classLoader, String className, UnsafeConsumer<CodeStream, MalformedJorth> generator, Consumer<CharSequence> printBack) throws MalformedJorth{
		Objects.requireNonNull(className);
		Objects.requireNonNull(generator);
		
		var jorth = new Jorth(classLoader, printBack);
		try(var writer = jorth.writer()){
			generator.accept(writer);
		}
		
		return jorth.getClassFile(className);
	}
	
	static{
		if(!Boolean.getBoolean("jorth.noPreload")){
			Preload.init();
		}
	}
	
	private int line;
	private int tab;
	
	private final TypeSource typeSource;
	
	private ClassGen    currentClass;
	private FunctionGen currentFunction;
	
	private       Visibility             visibilityBuffer;
	private       GenericType            extensionBuffer;
	private final List<GenericType>      interfaces  = new ArrayList<>();
	private final List<ClassName>        permits     = new ArrayList<>();
	private final EnumSet<Access>        accessSet   = EnumSet.noneOf(Access.class);
	private final Map<ClassName, AnnGen> annotations = new HashMap<>();
	
	private final Map<ClassName, GenericType> typeArgs = new HashMap<>();
	
	private final Map<String, ClassName>   imports = new HashMap<>();
	private final Map<ClassName, ClassGen> classes = new HashMap<>();
	
	private final Function<ClassName, ClassName> importsFun = this::resolveImport;
	private final Deque<Endable>                 endStack   = new ArrayDeque<>();
	
	private final Consumer<CharSequence> printBack;
	
	private boolean memberFlag;
	
	public Jorth(ClassLoader classLoader, Consumer<CharSequence> printBack){
		this.printBack = printBack;
		classLoader = classLoader == null? this.getClass().getClassLoader() : classLoader;
		typeSource = TypeSource.of(this::generatedClassInfo, classLoader);
	}
	
	private Optional<ClassInfo> generatedClassInfo(GenericType type){
		var name = type.raw();
		name = importsFun.apply(name);
		
		var jorthClass = classes.get(name);
		if(jorthClass != null){
			if(type.dims()>0){
				return makeArr(type);
			}
			return Optional.of(jorthClass);
		}
		
		var argType = typeArgs.get(name.dotted());
		if(argType != null){
			return typeSource.maybeByType(argType);
		}
		
		if(currentClass == null) return Optional.empty();
		if(!currentClass.name.equals(name)){
			return Optional.empty();
		}
		if(type.dims()>0){
			return makeArr(type);
		}
		return Optional.of(currentClass);
	}
	private Optional<ClassInfo> makeArr(GenericType type){
		try{
			return Optional.of(new ClassInfo.OfArray(typeSource, type.withDims(type.dims() - 1)));
		}catch(MalformedJorth e){
			throw new RuntimeException(e);
		}
	}
	
	public CodeStream writer(){
		return new Tokenizer(this, line);
	}
	
	@Override
	public void addImport(String clasName){
		var pos = clasName.lastIndexOf('.') + 1;
		
		var n = new StringBuilder(1 + clasName.length() - pos)
			        .append('#')
			        .append(clasName, pos, clasName.length());
		
		for(int i = 0; i<n.length() - 1; i++){
			if(n.charAt(i) == '$' && n.charAt(i + 1) != '$'){
				n.setCharAt(i, '.');
			}
		}
		
		imports.put(n.toString(), ClassName.dotted(clasName)
		);
	}
	@Override
	public void addImportAs(String clasName, String name){
		if(imports.put("#" + name, ClassName.dotted(clasName)) != null){
			throw new RuntimeException("Already imported " + name);
		}
	}
	private ClassName resolveImport(ClassName name){
		if(!name.any().startsWith("#")){
			return name;
		}
		var mapped = imports.get(name.dotted());
		if(mapped != null) return mapped;
		var info = typeSource.maybeByName(ClassName.dotted("java.lang." + name.dotted().substring(1)));
		if(info.isPresent()) return info.get().name();
		return name;
	}
	
	@Override
	protected TokenSource transform(TokenSource src){
		if(printBack == null) return src;
		return TokenSource.listen(src, tok -> {
			if(tok instanceof Token.KWord k){
				switch(k.keyword()){
					case START -> tab++;
					case END -> tab--;
				}
			}
			var toClassLevel = false;
			
			if(tok.line() != line){
				toClassLevel = tab == 1 && memberFlag;
				printBack.accept("\n" + "\t".repeat(tab));
				line = tok.line();
			}
			
			var tokStr = switch(tok){
				case Token.Word t when t.value().contains(" ") -> "\033[4m" + t.value();
				case Token.Word t -> t.value();
				case Token.EWord<?> t -> PURPLE_BRIGHT + (t.value() instanceof KeyedEnum e? e.key() : t.value().name().toLowerCase());
				case Token.KWord t -> CYAN_BRIGHT + t.keyword().key;
				case Token.StrValue t -> {
					var strCol = PURPLE_BRIGHT;
					yield strCol + "'" + t.value().replace("'", CYAN + "\\'" + strCol) + "'";
				}
				case Token.SmolWord w -> w.value() + "";
				case Token.NumToken t -> BLUE_BRIGHT + t.getNum();
				case Token.Null ignored -> BLUE_BRIGHT + "null";
				case Token.ClassWord t -> GREEN_BRIGHT + t.value();
				case Token.Bool bool -> GREEN_BRIGHT + bool.value();
				case Token.BracketedSet bracketedSet -> throw new IllegalStateException();
				case Token.Wildcard wildcard -> CYAN_BRIGHT + "?";
			};
			
			if(toClassLevel){
				memberFlag = false;
				printBack.accept("\n" + "\t".repeat(tab));
			}
			printBack.accept(tokStr + RESET);
		});
	}
	
	@Override
	protected void parse(TokenSource source) throws MalformedJorth{
		Token word;
		try{
			word = source.readToken();
		}catch(EndOfCode end){
			return;
		}
		
		if(word instanceof Token.EWord<?> w){
			switch(w.value()){
				case Visibility vis -> {
					if(currentFunction != null) throw new MalformedJorth("Can not define visibility inside function");
					if(visibilityBuffer != null) throw new MalformedJorth("Visibility already defined");
					visibilityBuffer = vis;
					return;
				}
				case Access acc -> {
					if(!accessSet.add(acc)){
						throw new MalformedJorth(acc + " already defined");
					}
					return;
				}
				default -> { }
			}
		}
		
		var oKey = word.as(Token.KWord.class);
		if(oKey.isPresent()){
			var keyword = oKey.get().keyword();
			if(anyKeyword(source, keyword)){
				return;
			}
			if(currentFunction != null){
				functionKeyword(source, keyword);
				return;
			}
			if(currentClass != null){
				classKeyword(source, keyword);
				return;
			}
			topKeyword(source, keyword);
			return;
		}
		
		
		var ok = true;
		
		if(currentFunction != null){
			switch(word){
				case Token.NumToken.FloatVal fVal -> requireFunction().loadFloatOp(fVal.value());
				case Token.NumToken.IntVal intVal -> requireFunction().loadIntOp(intVal.value());
				case Token.StrValue sVal -> requireFunction().loadStringOp(sVal.value());
				case Token.Bool booleVal -> requireFunction().loadBooleanOp(booleVal.value());
				default -> {
					var oOp = word.asEnum(Operation.class);
					if(oOp.isPresent()){
						var op = oOp.get();
						
						switch(op){
							case EQUALS -> currentFunction.compareEqualOp(true);
							case NOT_EQUALS -> currentFunction.compareEqualOp(false);
							default -> throw new NotImplementedException(op + " not implemented");
						}
						return;
					}
					ok = false;
				}
			}
			
		}else ok = false;
		
		if(!ok){
			throw new MalformedJorth("Unexpected token " + word);
		}
	}
	
	private void classKeyword(TokenSource source, Keyword keyword) throws MalformedJorth{
		switch(keyword){
			case FIELD -> {
				var name = source.readWord();
				var type = readType(source, typeArgs);
				currentClass.defineField(popVisibility(), popAccessSet(), popAnnotations(), type, name);
				memberFlag = true;
			}
			case FUNCTION -> {
				var functionName = source.readWord();
				if(functionName.equals("<")){
					functionName = "<" + source.readWord() + ">";
					source.requireWord(">");
				}
				
				tab++;
				JType returnType = null;
				var   args       = new LinkedHashMap<String, FunctionGen.ArgInfo>();
				
				var vis  = popVisibility();
				var acc  = popAccessSet();
				var anns = popAnnotations();
				
				boolean interf = currentClass.type == ClassType.INTERFACE;
				if(interf && vis != Visibility.PUBLIC) throw new MalformedJorth("Interface members must be public");
				
				boolean noBody = interf && !acc.contains(Access.STATIC);
				
				propCollect:
				while(true){
					if(source.peekToken() instanceof Token.KWord k && k.keyword() == Keyword.START){
						tab -= 2;
					}
					var k = source.readKeyword();
					if(noBody && k == Keyword.END){
						break;
					}
					switch(k){
						case START -> {
							if(noBody){
								throw new MalformedJorth("Can not start body of the function. Please use end instead of start");
							}
							tab++;
							break propCollect;
						}
						case ARG -> {
							var name = source.readWord();
							var type = readType(source, typeArgs);
							
							var access = popAccessSet();
							if(!access.isEmpty()){
								throw new MalformedJorth("Unsupported access on " + name + ": " + access);
							}
							
							if(args.put(name, new FunctionGen.ArgInfo(type, name)) != null){
								throw new MalformedJorth("Duplicate arg " + name);
							}
						}
						case RETURNS -> {
							if(returnType != null) throw new MalformedJorth("Duplicate returns statement");
							returnType = readType(source, typeArgs);
						}
						default -> throw new MalformedJorth("Unexpected keyword " + k.key);
					}
				}
				
				
				currentFunction = currentClass.defineFunction(functionName, vis, acc, returnType, args.values(), anns);
				if(noBody){
					endFunction();
				}else{
					endStack.addLast(this::endFunction);
				}
			}
			case ENUM -> {
				var enumName = source.readWord();
				
				currentClass.defineField(
					Visibility.PUBLIC,
					EnumSet.of(Access.STATIC, Access.FINAL, Access.ENUM),
					List.of(),
					new GenericType(currentClass.name),
					enumName
				);
				
			}
			default -> throw new MalformedJorth("Unexpected keyword " + keyword.key + " in class " + currentClass);
		}
	}
	
	private JType readType(TokenSource source, Map<ClassName, GenericType> typeArgs) throws MalformedJorth{
		var type = source.readType(importsFun, typeArgs);
		typeSource.validateType(type);
		return type;
	}
	private ClassName getReadClassName(TokenSource source) throws MalformedJorth{ return source.readClassName(importsFun); }
	
	private boolean anyKeyword(TokenSource source, Keyword keyword) throws MalformedJorth{
		switch(keyword){
			case END -> {
				if(endStack.isEmpty()) throw new MalformedJorth("Stray end");
				var e = endStack.removeLast();
				e.end();
			}
			case AT -> {
				var annType = getReadClassName(source);
				if(annotations.containsKey(annType)){
					throw new MalformedJorth("Annotation " + annType + " already defined");
				}
				var tInfo = typeSource.byName(annType);
				if(tInfo.type() != ClassType.ANNOTATION){
					throw new MalformedJorth("Used non annotation class " + annType + " as annotation");
				}
				
				record EnumValue(GenericType type, Object val){ }
				
				var typeMap = new HashMap<String, EnumValue>();
				
				tInfo.getFunctions().filter(m -> {
					if(m.name().equals("annotationType")) return false;
					var n = m.owner().name();
					return n.equals(annType);
				}).forEach(f -> typeMap.put(f.name(), new EnumValue(f.returnType().asGeneric(), f.defaultEnumValue())));
				
				var hasStart = optionalStart(source);
				
				var args = new HashMap<String, Object>();
				
				if(hasStart){
					while(!source.consumeTokenIf(Token.KWord.class, w -> w.keyword() == Keyword.END)){
						var name = source.readWord();
						if(args.containsKey(name)){
							throw new MalformedJorth("Duplicate field name in enum");
						}
						
						var type = typeMap.get(name);
						if(type == null) throw new MalformedJorth(name + " does not exist in " + annType);
						
						args.put(name, readValue(type.type, source));
					}
				}
				
				annotations.put(annType, new AnnGen(annType, args));
			}
			default -> {
				return false;
			}
		}
		return true;
	}
	
	
	private Object readValue(GenericType type, TokenSource source) throws MalformedJorth{
		if(type.dims() == 0){
			return readValue(type.raw(), source);
		}
		return parseBracketSet(type.raw(), type.dims(), source.bracketSet('['));
	}
	
	private Object parseBracketSet(ClassName type, int dims, Token.BracketedSet source) throws MalformedJorth{
		var t = type.baseType().type;
		for(int i = 1; i<dims; i++){
			t = t.arrayType();
		}
		var arr = Array.newInstance(t, source.contents().size());
		
		if(dims>1){
			for(int i = 0; i<source.contents().size(); i++){
				Array.set(arr, i, parseBracketSet(type, dims - 1, source.contents().get(i).requireAs(Token.BracketedSet.class)));
			}
		}else{
			for(int i = 0; i<source.contents().size(); i++){
				var v = source.contents().get(i);
				Array.set(arr, i, readValue(type, TokenSource.of(() -> v)));
			}
		}
		return arr;
	}
	private Object readValue(ClassName type, TokenSource source) throws MalformedJorth{
		return switch(BaseType.of(type)){
			case OBJ -> {
				var info = typeSource.byName(type);
				if(info.type() == ClassType.ENUM){
					for(Enum<?> e : info.enumConstantNames()){
						if(source.consumeTokenIfIsText(e.name())){
							yield e;
						}
					}
					
					String t;
					try{
						t = source.readToken().toString();
					}catch(EndOfCode e){
						t = "<EOC>";
					}
					throw new MalformedJorth("Expected any of " + info.enumConstantNames() + " but got " + t);
				}else{
					if(type.equals(ClassName.of(String.class))){
						yield source.readToken().requireAs(Token.StrValue.class).value();
					}
					if(type.equals(ClassName.of(Class.class))){
						yield source.readClassName(importsFun);
					}
					throw new MalformedJorth("Unknown type: " + type);
				}
			}
			case CHAR -> source.readChar();
			case BYTE -> (byte)source.readInt();
			case SHORT -> (short)source.readInt();
			case INT -> source.readInt();
			case LONG -> (long)source.readInt();
			case FLOAT -> source.readFloat();
			case DOUBLE -> (double)source.readFloat();
			case BOOLEAN -> source.readBool();
			case VOID -> throw new IllegalArgumentException();
		};
	}
	
	private void functionKeyword(TokenSource source, Keyword keyword) throws MalformedJorth{
		switch(keyword){
			case GET -> {
				if(source.consumeTokenIfIsText("this")){
					var member = source.readWord();
					currentFunction.getThisOp(member);
				}else if(source.consumeTokenIfIsText("#arg")){
					var member = source.readWord();
					currentFunction.getArgOp(member);
				}else{
					var owner  = source.readClassName(importsFun);
					var member = source.readWord();
					currentFunction.getStaticOp(owner, member);
				}
			}
			case SET -> {
				if(source.consumeTokenIfIsText("this")){
					var member = source.readWord();
					currentFunction.loadThisIns();
					currentFunction.swapOp();
					currentFunction.setInstanceOp(member);
				}else{
					var owner  = source.readClassName(importsFun);
					var member = source.readWord();
					currentFunction.setStaticOp(owner, member);
				}
			}
			case CAST -> {
				var clazz = getReadClassName(source);
				currentFunction.castOp(new GenericType(clazz));
			}
			case IF -> {
				source.requireKeyword(Keyword.START);
				currentFunction.pushIfBool();
				endStack.add(currentFunction::popIf);
			}
			case RETURN -> currentFunction.returnOp();
			case THROW -> currentFunction.throwOp();
			case NEW -> {
				var clazz = getReadClassName(source);
				currentFunction.newOp(new GenericType(clazz));
				currentFunction.dupOp();
				doCall(source, null, "<init>");
			}
			case CALL -> {
				ClassName staticOwner = null;
				
				var acc = popAccessSet();
				if(acc.remove(Access.STATIC)) staticOwner = getReadClassName(source);
				if(!acc.isEmpty()) throw new MalformedJorth("Illegal access " + acc);
				
				var funName = source.readWord();
				doCall(source, staticOwner, funName);
			}
			case SUPER -> {
				currentFunction.loadThisIns();
				var hasStart = optionalStart(source);
				
				var parent = currentClass.superType();
				var call   = currentFunction.startCallRaw(parent, "<init>", true);
				if(hasStart) endStack.add(call);
				else call.end();
			}
			case DUP -> currentFunction.dupOp();
			case POP -> currentFunction.popOp();
			case CLASS -> {
				var clazz = source.readClassName(importsFun);
				currentFunction.loadClassTypeOp(clazz);
			}
			case WHAT_THE_STACK -> {
				throw new MalformedJorth("Debug token '???' at line " + source.line() + " encountered. Current stack:\n" + currentFunction.getStack());
			}
			default -> throw new MalformedJorth("Unexpected keyword " + keyword.key + " in function " + currentFunction);
		}
	}
	
	private void doCall(TokenSource source, ClassName staticOwner, String funName) throws MalformedJorth{
		var hasStart = optionalStart(source);
		var call     = currentFunction.startCall(staticOwner, funName);
		if(hasStart){
			endStack.add(call);
		}else{
			call.end();
		}
	}
	private static boolean optionalStart(TokenSource source) throws MalformedJorth{
		return source.consumeTokenIf(Token.KWord.class, w -> w.keyword() == Keyword.START);
	}
	
	private void topKeyword(TokenSource source, Keyword keyword) throws MalformedJorth{
		switch(keyword){
			case INTERFACE, CLASS, ENUM -> {
				var className = getReadClassName(source);
				source.requireKeyword(Keyword.START);
				
				if(classes.containsKey(className)){
					throw new MalformedJorth(className + " already defined or started");
				}
				
				var visibility = popVisibility();
				var extension  = popExtension();
				var interfaces = popInterfaces();
				var permits    = popPermits();
				var anns       = popAnnotations();
				var accessSet  = popAccessSet();
				
				if(keyword == Keyword.ENUM){
					if(!extension.equals(GenericType.OBJECT)){
						throw new MalformedJorth("Can not extend enum");
					}
					if(!permits.isEmpty()){
						throw new MalformedJorth("Enum can not be sealed");
					}
					extension = new GenericType(ClassName.of(Enum.class), Optional.empty(), 0, List.of(new GenericType(className)));
				}
				
				currentClass = new ClassGen(typeSource, className, ClassType.from(keyword), visibility, extension, interfaces, permits, accessSet, anns, typeArgs);
				classes.put(className, currentClass);
				
				typeSource.validateType(extension);
				for(var interf : interfaces){
					typeSource.validateType(interf);
				}
				endStack.addLast(this::endClass);
			}
			case EXTENDS -> {
				if(extensionBuffer != null) throw new MalformedJorth("Super class already defined");
				extensionBuffer = source.readTypeSimple(importsFun, typeArgs);
				typeSource.validateType(extensionBuffer.raw());
			}
			case IMPLEMENTS -> {
				var interf = source.readTypeSimple(importsFun, typeArgs);
				typeSource.validateType(interf.raw());
				interfaces.add(interf);
			}
			case PERMITS -> {
				var permit = source.readClassName(importsFun);
//				typeSource.validateType(permit);
				permits.add(permit);
			}
			case TYPE_ARG -> {
				var name = ClassName.dotted(source.readWord());
				if(typeArgs.containsKey(name)){
					throw new MalformedJorth(name + " is already a defined type argument");
				}
				var type = source.readTypeSimple(importsFun, Map.of());
				typeArgs.put(name, type);
			}
			default -> throw new MalformedJorth("Unexpected keyword " + keyword.key);
		}
	}
	
	private void endFunction() throws MalformedJorth{
		currentFunction.end();
		currentFunction = null;
		memberFlag = true;
	}
	
	private void endClass() throws MalformedJorth{
		currentClass.end();
		typeArgs.clear();
		currentClass = null;
		memberFlag = true;
	}
	
	private FunctionGen requireFunction() throws MalformedJorth{
		if(currentFunction != null) return currentFunction;
		throw new MalformedJorth("Not inside function");
	}
	
	private Visibility popVisibility(){
		var tmp = visibilityBuffer;
		visibilityBuffer = null;
		if(tmp == null) return Visibility.PUBLIC;
		return tmp;
	}
	private GenericType popExtension(){
		var tmp = extensionBuffer;
		extensionBuffer = null;
		if(tmp == null) tmp = GenericType.OBJECT;
		return tmp;
	}
	private List<GenericType> popInterfaces(){
		var tmp = List.copyOf(interfaces);
		interfaces.clear();
		return tmp;
	}
	private List<ClassName> popPermits(){
		var tmp = List.copyOf(permits);
		permits.clear();
		return tmp;
	}
	private EnumSet<Access> popAccessSet(){
		var tmp = EnumSet.copyOf(accessSet);
		accessSet.clear();
		return tmp;
	}
	private List<AnnGen> popAnnotations(){
		var tmp = List.copyOf(annotations.values());
		annotations.clear();
		return tmp;
	}
	
	public Set<String> listClassFiles(){
		return classes.keySet().stream().map(ClassName::dotted).collect(Collectors.toSet());
	}
	public byte[] getClassFile(String name){
		var cls = classes.get(ClassName.dotted(name));
		if(cls == null){
			throw new IllegalArgumentException(name + " is not defined");
		}
		
		var file = cls.getClassFile();
		if(file == null){
			throw new IllegalStateException("Class " + name + " not ended");
		}
		
		return file;
	}
}
