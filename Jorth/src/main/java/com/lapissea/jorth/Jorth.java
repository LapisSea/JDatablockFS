package com.lapissea.jorth;

import com.lapissea.jorth.lang.*;
import com.lapissea.jorth.lang.type.*;
import com.lapissea.util.NotImplementedException;

import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.lapissea.util.ConsoleColors.*;

public final class Jorth extends CodeDestination{
	
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
	private final Set<Access>            accessSet   = EnumSet.noneOf(Access.class);
	private final Map<ClassName, AnnGen> annotations = new HashMap<>();
	
	private final Map<String, ClassName>   imports = new HashMap<>();
	private final Map<ClassName, ClassGen> classes = new HashMap<>();
	
	private final Function<ClassName, ClassName> importsFun = this::resolveImport;
	private final Deque<Endable>                 endStack   = new ArrayDeque<>();
	
	private final Consumer<CharSequence> printBack;
	
	public Jorth(ClassLoader classLoader, Consumer<CharSequence> printBack){
		this.printBack = printBack;
		classLoader = classLoader == null? this.getClass().getClassLoader() : classLoader;
		typeSource = TypeSource.concat(this::generatedClassInfo, TypeSource.of(classLoader));
	}
	
	private Optional<ClassInfo> generatedClassInfo(GenericType type){
		var name = type.raw();
		name = importsFun.apply(name);
		
		if(currentClass == null || !currentClass.name.equals(name)){
			return Optional.empty();
		}
		if(type.dims()>0){
			try{
				return Optional.of(new ClassInfo.OfArray(typeSource, type.withDims(type.dims() - 1)));
			}catch(MalformedJorth e){
				throw new RuntimeException(e);
			}
		}
		return Optional.of(currentClass);
	}
	
	public CodeStream writer(){
		return new Tokenizer(this, line);
	}
	public CodeStream writer(Executor executor){
		return new Tokenizer(this, line, executor);
	}
	
	@Override
	public void addImport(String clasName){
		var pos = clasName.lastIndexOf('.') + 1;
		imports.put(
			new StringBuilder(1 + clasName.length() - pos)
				.append('#')
				.append(clasName, pos, clasName.length())
				.toString(),
			ClassName.dotted(clasName)
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
			
			if(tok.line() != line){
				printBack.accept("\n" + "\t".repeat(tab));
				line = tok.line();
			}
			
			var tokStr = switch(tok){
				case Token.Word t when t.value().contains(" ") -> "\033[4m" + t.value();
				case Token.Word t -> t.value();
				case Token.EWord<?> t -> PURPLE_BRIGHT + t.value();
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
			};
			
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
		
		if(!ok) throw new MalformedJorth("Unexpected token " + word);
	}
	
	private void classKeyword(TokenSource source, Keyword keyword) throws MalformedJorth{
		switch(keyword){
			case FIELD -> {
				var anns = popAnnotations();
				var name = source.readWord();
				var type = readType(source);
				currentClass.defineField(popVisibility(), Set.of(), anns, type, name);
			}
			case FUNCTION -> {
				var functionName = source.readWord();
				if(functionName.equals("<")){
					functionName = "<" + source.readWord() + ">";
					source.requireWord(">");
				}
				
				tab++;
				GenericType returnType = null;
				var         args       = new LinkedHashMap<String, FunctionGen.ArgInfo>();
				
				var vis  = popVisibility();
				var acc  = popAccessSet();
				var anns = popAnnotations();
				
				propCollect:
				while(true){
					if(source.peekToken() instanceof Token.KWord k && k.keyword() == Keyword.START){
						tab -= 2;
					}
					switch(source.readKeyword()){
						case START -> {
							tab++;
							break propCollect;
						}
						case ARG -> {
							var name = source.readWord();
							var type = readType(source);
							
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
							returnType = readType(source);
						}
						default -> throw new MalformedJorth("Unexpected keyword " + keyword.key);
					}
				}
				
				
				currentFunction = currentClass.defineFunction(functionName, vis, acc, returnType, args.values(), anns);
				endStack.addLast(this::endFunction);
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
	
	private GenericType readType(TokenSource source) throws MalformedJorth      { return source.readType(importsFun); }
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
				
				var hasStart = optionalStart(source);
				
				var args = new HashMap<String, Object>();
				
				if(hasStart){
					while(!source.consumeTokenIf(Token.KWord.class, w -> w.keyword() == Keyword.END)){
						var name = source.readWord();
						if(args.containsKey(name)){
							throw new MalformedJorth("Duplicate field name in enum");
						}
						
						
						var tok = source.readToken();
						var value = switch(tok){
							case Token.NumToken t -> t.getNum();
							case Token.StrValue t -> t.value();
							case Token.Bool t -> t.value();
							default -> throw new MalformedJorth("Illegal token " + tok + " inside enum argument block");
						};
						
						args.put(name, value);
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
	
	private void functionKeyword(TokenSource source, Keyword keyword) throws MalformedJorth{
		switch(keyword){
			case GET -> {
				var owner  = source.readWord();
				var member = source.readWord();
				currentFunction.getOp(owner, member);
			}
			case SET -> {
				var owner  = source.readWord();
				var member = source.readWord();
				currentFunction.setOp(owner, member);
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
				currentFunction.superOp(List.of());
			}
			case DUP -> currentFunction.dupOp();
			case POP -> currentFunction.popOp();
			case WHAT_THE_STACK -> throw new MalformedJorth("Debug token '???' at line " + source.line() + " encountered. Current stack:\n" + currentFunction.getStack());
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
				var anns       = popAnnotations();
				
				if(keyword == Keyword.ENUM){
					if(!extension.equals(GenericType.OBJECT)){
						throw new MalformedJorth("Can not extend enum");
					}
					extension = new GenericType(ClassName.of(Enum.class), 0, List.of(new GenericType(className)));
				}
				
				currentClass = new ClassGen(typeSource, className, ClassType.from(keyword), visibility, extension, interfaces, accessSet, anns);
				classes.put(className, currentClass);
				endStack.addLast(this::endClass);
			}
			case EXTENDS -> {
				if(extensionBuffer != null) throw new MalformedJorth("Super class already defined");
				extensionBuffer = source.readType(importsFun, false);
			}
			case IMPLEMENTS -> interfaces.add(source.readType(importsFun, false));
			default -> throw new MalformedJorth("Unexpected keyword " + keyword.key);
		}
	}
	
	private void endFunction() throws MalformedJorth{
		currentFunction.end();
		currentFunction = null;
	}
	
	private void endClass() throws MalformedJorth{
		currentClass.end();
		currentClass = null;
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
	
	public byte[] getClassFile(String name){
		var cls = classes.get(ClassName.dotted(name));
		
		var file = cls.getClassFile();
		if(file == null) throw new IllegalStateException("Class not ended");
		
		return file;
	}
}
