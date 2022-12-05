package com.lapissea.jorth.v2;

import com.lapissea.jorth.MalformedJorthException;
import com.lapissea.jorth.v2.lang.*;
import com.lapissea.jorth.v2.lang.type.*;
import com.lapissea.util.LogUtil;
import com.lapissea.util.NotImplementedException;

import java.util.*;
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
	
	private       Visibility        visibilityBuffer;
	private       GenericType       extensionBuffer;
	private final List<GenericType> interfaces = new ArrayList<>();
	private final Set<Access>       accessSet  = EnumSet.noneOf(Access.class);
	
	private final Map<String, ClassName> imports = new HashMap<>();
	
	private final Map<ClassName, ClassGen> classes = new HashMap<>();
	
	private final Function<ClassName, ClassName> importsFun = this::resolveImport;
	
	private final Deque<Endable> endStack = new ArrayDeque<>();
	
	private final boolean printBack;
	
	public Jorth(ClassLoader classLoader, boolean printBack){
		this.printBack = printBack;
		classLoader = classLoader == null? this.getClass().getClassLoader() : classLoader;
		typeSource = TypeSource.concat(type -> {
			var name = type.raw();
			name = importsFun.apply(name);
			if(currentClass == null || !currentClass.name.equals(name)) return Optional.empty();
			if(type.dims() != 0){
				throw new NotImplementedException();
			}
			return Optional.of(currentClass);
		}, TypeSource.of(classLoader));
	}
	
	public CodeStream writer(){
		return new Tokenizer(this, line);
	}
	
	@Override
	public void addImport(Class<?> type){
		imports.put("#" + type.getSimpleName(), ClassName.of(type));
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
		if(!printBack) return src;
		return TokenSource.listen(src, tok -> {
			if(tok instanceof Token.KWord k){
				switch(k.keyword()){
					case START -> tab++;
					case END -> tab--;
				}
			}
			
			if(tok.line() != line){
				LogUtil.print("\n" + "\t".repeat(tab));
				line = tok.line();
			}
			
			LogUtil.print(switch(tok){
				case Token.Word t when t.value().contains(" ") -> "\033[4m" + t.value();
				case Token.Word t -> t.value();
				case Token.EWord<?> t -> PURPLE_BRIGHT + t.value();
				case Token.KWord t -> CYAN_BRIGHT + t.keyword().key;
				case Token.StrValue t -> {
					var strCol = PURPLE_BRIGHT;
					yield strCol + "'" + t.value().replace("'", CYAN + "\\'" + strCol) + "'";
				}
				case Token.SmolWord w -> w.value() + "";
				case Token.IntVal t -> BLUE_BRIGHT + t.value();
				case Token.FloatVal t -> BLUE_BRIGHT + t.value();
				case Token.Null ignored -> BLUE_BRIGHT + "null";
				case Token.CWord t -> GREEN_BRIGHT + t.value();
				case Token.Bool bool -> GREEN_BRIGHT + bool.value();
			} + RESET + " ");
		});
	}
	
	@Override
	protected void parse(TokenSource source) throws MalformedJorthException{
		var word = source.readToken();
		
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
		
		
		switch(word){
			case Token.FloatVal fVal -> requireFunction().loadFloatOp(fVal.value());
			case Token.IntVal intVal -> requireFunction().loadIntOp(intVal.value());
			case Token.StrValue sVal -> requireFunction().loadStringOp(sVal.value());
			case Token.Bool booleVal -> requireFunction().loadBooleanOp(booleVal.value());
			default -> throw new MalformedJorthException("Unexpected token " + word);
		}
	}
	
	private void classKeyword(TokenSource source, Keyword keyword) throws MalformedJorthException{
		switch(keyword){
			case FIELD -> {
				var name = source.readWord();
				var type = source.readType(importsFun);
				currentClass.defineField(popVisibility(), Set.of(), type, name);
			}
			case FUNCTION -> {
				if(currentFunction != null) throw new MalformedJorthException("Already inside function");
				
				var functionName = source.readWord();
				if(functionName.equals("<")){
					functionName = "<" + source.readWord() + ">";
					source.requireWord(">");
				}
				
				tab++;
				GenericType returnType = null;
				var         args       = new LinkedHashMap<String, FunctionGen.ArgInfo>();
				
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
							var type = source.readType(importsFun);
							
							var     access   = popAccessSet();
							boolean isStatic = access.remove(Access.STATIC);
							if(!access.isEmpty()){
								throw new MalformedJorthException("Unsupported access on " + name + ": " + access);
							}
							
							if(args.put(name, new FunctionGen.ArgInfo(type, name, isStatic)) != null){
								throw new MalformedJorthException("Duplicate arg " + name);
							}
						}
						case RETURNS -> {
							if(returnType != null) throw new MalformedJorthException("Duplicate returns statement");
							returnType = source.readType(importsFun);
						}
						default -> throw new MalformedJorthException("Unexpected keyword " + keyword);
					}
				}
				
				
				currentFunction = currentClass.defineFunction(functionName, popVisibility(), popAccessSet(), returnType, args);
				endStack.addLast(this::endFunction);
			}
			default -> throw new MalformedJorthException("Unexpected keyword " + keyword + " in class " + currentClass);
		}
	}
	
	private boolean anyKeyword(TokenSource source, Keyword keyword) throws MalformedJorthException{
		switch(keyword){
			case END -> {
				if(endStack.isEmpty()) throw new MalformedJorthException("Stray end");
				var e = endStack.removeLast();
				e.end();
			}
			case VISIBILITY -> {
				if(currentFunction != null) throw new MalformedJorthException("Can not define visibility inside function");
				if(visibilityBuffer != null) throw new MalformedJorthException("Visibility already defined");
				visibilityBuffer = source.readEnum(Visibility.class);
			}
			case ACCESS -> {
				var e = source.readEnum(Access.class);
				if(!accessSet.add(e)){
					throw new MalformedJorthException(e + " already defined");
				}
			}
			default -> {
				return false;
			}
		}
		return true;
	}
	
	private void functionKeyword(TokenSource source, Keyword keyword) throws MalformedJorthException{
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
			case EQUALS -> currentFunction.equalsOp();
			case IF -> {
				source.requireKeyword(Keyword.START);
				currentFunction.pushIfBool();
				endStack.add(currentFunction::popIf);
			}
			case RETURN -> currentFunction.returnOp();
			case THROW -> currentFunction.throwOp();
			case NEW -> {
				var clazz = source.readClassName(importsFun);
				source.requireKeyword(Keyword.START);
				currentFunction.newOp(clazz);
				currentFunction.dupOp();
				endStack.add(currentFunction.startCall(null, "<init>"));
			}
			case CALL -> {
				ClassName staticOwner = null;
				
				var acc = popAccessSet();
				if(acc.remove(Access.STATIC)) staticOwner = source.readClassName(importsFun);
				if(!acc.isEmpty()) throw new MalformedJorthException("Illegal access " + acc);
				
				var functionName = source.readWord();
				source.requireKeyword(Keyword.START);
				endStack.add(currentFunction.startCall(staticOwner, functionName));
			}
			case SUPER -> currentFunction.superOp();
			case DUP -> currentFunction.dupOp();
			case POP -> currentFunction.popOp();
			case WHAT_THE_STACK -> throw new MalformedJorthException("Debug token '???' at line " + source.line() + " encountered. Current stack:\n" + currentFunction.getStack());
			default -> throw new MalformedJorthException("Unexpected keyword " + keyword + " in function " + currentFunction);
		}
	}
	
	private void topKeyword(TokenSource source, Keyword keyword) throws MalformedJorthException{
		switch(keyword){
			case INTERFACE, CLASS, ENUM -> {
				var className = source.readClassName(importsFun);
				source.requireKeyword(Keyword.START);
				
				if(classes.containsKey(className)){
					throw new MalformedJorthException(className + " already defined or started");
				}
				
				var visibility = popVisibility();
				var extension  = popExtension();
				
				currentClass = new ClassGen(typeSource, className, ClassType.from(keyword), visibility, extension, interfaces, accessSet);
				classes.put(className, currentClass);
				this.interfaces.clear();
				endStack.addLast(this::endClass);
			}
			case EXTENDS -> {
				if(extensionBuffer != null) throw new MalformedJorthException("Super class already defined");
				extensionBuffer = source.readType(importsFun, false);
			}
			case IMPLEMENTS -> interfaces.add(source.readType(importsFun, false));
			default -> throw new MalformedJorthException("Unexpected keyword " + keyword);
		}
	}
	
	private void endFunction() throws MalformedJorthException{
		currentFunction.end();
		currentFunction = null;
	}
	
	private void endClass() throws MalformedJorthException{
		currentClass.end();
		currentClass = null;
	}
	
	private FunctionGen requireFunction() throws MalformedJorthException{
		if(currentFunction != null) return currentFunction;
		throw new MalformedJorthException("Not inside function");
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
	private EnumSet<Access> popAccessSet(){
		var tmp = EnumSet.copyOf(accessSet);
		accessSet.clear();
		return tmp;
	}
	
	public byte[] getClassFile(String name){
		var cls = classes.get(ClassName.dotted(name));
		
		var file = cls.getClassFile();
		if(file == null) throw new IllegalStateException("Class not ended");
		
		return file;
	}
}
