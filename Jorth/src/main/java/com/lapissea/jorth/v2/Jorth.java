package com.lapissea.jorth.v2;

import com.lapissea.jorth.MalformedJorthException;
import com.lapissea.jorth.v2.lang.*;
import com.lapissea.jorth.v2.lang.type.*;
import com.lapissea.util.LogUtil;
import com.lapissea.util.NanoTimer;
import com.lapissea.util.UtilL;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;

import java.lang.invoke.MethodHandles;
import java.util.*;
import java.util.regex.Pattern;

import static com.lapissea.util.ConsoleColors.*;

public final class Jorth extends CodeDestination{
	
	private static void preload(MethodHandles.Lookup l, Class<?> cls){
		Thread.ofVirtual().start(()->{
			try{
				l.ensureInitialized(cls);
			}catch(Exception ignored){}
			
			if(cls.isInterface()){
				var permitted=cls.getPermittedSubclasses();
				if(permitted!=null){
					for(Class<?> c : cls.getClasses()){
						preload(l, c);
					}
				}
			}
		});
	}
	
	static{
		Thread.ofVirtual().start(()->{
			var l=MethodHandles.lookup();
			for(var cls : List.of(
				GenericType.class, ClassType.class, Visibility.class, Access.class,
				Token.class, TypeSource.class,
				ClassGen.class, ClassWriter.class,
				ClassGen.FieldGen.class, FieldVisitor.class,
				FunctionGen.class, MethodVisitor.class,
				Pattern.class, UtilL.class, NanoTimer.Simple.class,
				Tokenizer.class
			)){
				preload(l, cls);
			}
		});
	}
	
	private int line;
	private int tab;
	
	private final TypeSource source;
	
	private ClassGen    currentClass;
	private FunctionGen currentFunction;
	
	private       Visibility        visibilityBuffer;
	private       GenericType       extensionBuffer;
	private final List<GenericType> interfaces=new ArrayList<>();
	
	private final Deque<Endable> startStack=new ArrayDeque<>();
	
	public Jorth(ClassLoader classLoader){
		classLoader=classLoader==null?this.getClass().getClassLoader():classLoader;
		source=TypeSource.concat(name->{
			if(currentClass==null||!currentClass.name.equals(name)) return Optional.empty();
			return Optional.of(currentClass);
		}, TypeSource.of(classLoader));
	}
	
	public CodeStream writer(){
		return new Tokenizer(this, line);
	}
	
	@Override
	protected TokenSource transform(TokenSource src){
//		if(true)return src;
		return TokenSource.listen(src, tok->{
			if(tok instanceof Token.KWord k){
				switch(k.keyword()){
					case START -> tab++;
					case END -> tab--;
				}
			}
			
			if(tok.line()!=line){
				LogUtil.print("\n"+"\t".repeat(tab));
				line=tok.line();
			}
			
			LogUtil.print(switch(tok){
				case Token.Word t when t.value().contains(" ") -> "\033[4m"+t.value();
				case Token.Word t -> t.value();
				case Token.EWord<?> t -> PURPLE_BRIGHT+t.value();
				case Token.KWord t -> CYAN_BRIGHT+t.keyword();
				case Token.StrValue t -> {
					var strCol=PURPLE_BRIGHT;
					yield strCol+"'"+t.value().replace("'", CYAN+"\\'"+strCol)+"'";
				}
				case Token.IntVal t -> BLUE_BRIGHT+t.value();
				case Token.FloatVal t -> BLUE_BRIGHT+t.value();
				case Token.Null ignored -> BLUE_BRIGHT+"null";
			}+RESET+" ");
		});
	}
	
	@Override
	protected void parse(TokenSource source) throws MalformedJorthException{
		var keyword=source.readKeyword();
		switch(keyword){
			case DEFINE -> {
				var value=source.readWord();
				source.requireKeyword(Keyword.AS);
				var key=source.readWord();
				
				source.addDefinition(value, key);
			}
			case INTERFACE, CLASS, ENUM -> {
				var className=source.readWord();
				source.requireKeyword(Keyword.START);
				
				var visibility=popVisibility();
				var extension =popExtension();
				
				currentClass=new ClassGen(ClassName.dotted(className), ClassType.from(keyword), visibility, extension, interfaces);
				this.interfaces.clear();
				startStack.addLast(currentClass);
			}
			case FIELD -> {
				requireClass();
				var name=source.readWord();
				var type=source.readType();
				currentClass.defineField(popVisibility(), Set.of(), type, name);
			}
			case FUNCTION -> {
				requireClass();
				if(currentFunction!=null) throw new MalformedJorthException("Already inside function");
				
				var functionName=source.readWord();
				
				tab++;
				GenericType returns=null;
				var         args   =new ArrayList<FunctionGen.Arg>();
				
				while(true){
					if(source.peekToken() instanceof Token.KWord k&&k.keyword()==Keyword.START){
						tab-=2;
					}
					switch(source.readKeyword()){
						case START -> {
							tab++;
							currentFunction=currentClass.defineFunction(functionName, returns, args);
							startStack.add(currentFunction);
							return;
						}
						case ARG -> {
							var name=source.readWord();
							var type=source.readType();
							args.add(new FunctionGen.Arg(type, name));
						}
						case RETURNS -> {
							if(returns!=null) throw new MalformedJorthException("Duplicate returns statement");
							returns=source.readType();
						}
						default -> throw new MalformedJorthException("Unexpected keyword "+keyword);
					}
				}
			}
			case VISIBILITY -> {
				if(visibilityBuffer!=null) throw new MalformedJorthException("Visibility already defined");
				visibilityBuffer=source.readEnum(Visibility.class).value();
			}
			case EXTENDS -> {
				if(extensionBuffer!=null) throw new MalformedJorthException("Super class already defined");
				extensionBuffer=source.readType(false);
			}
			case IMPLEMENTS -> interfaces.add(source.readType(false));
			case END -> {
				if(startStack.isEmpty()) throw new MalformedJorthException("Stray end");
				startStack.removeLast().end();
			}
			case GET -> {
				requireFunction();
				var owner =source.readWord();
				var member=source.readWord();
				currentClass.getOp(owner, member);
			}
			default -> throw new MalformedJorthException("Unexpected keyword "+keyword);
		}
	}
	
	private void requireClass() throws MalformedJorthException{
		if(currentClass!=null) return;
		throw new MalformedJorthException("Not inside class");
	}
	private void requireFunction() throws MalformedJorthException{
		if(currentFunction!=null) return;
		throw new MalformedJorthException("Not inside function");
	}
	
	private Visibility popVisibility(){
		var visibility=visibilityBuffer;
		visibilityBuffer=null;
		if(visibility==null) return Visibility.PUBLIC;
		return visibility;
	}
	private GenericType popExtension(){
		var extension=extensionBuffer;
		extensionBuffer=null;
		if(extension==null) extension=GenericType.OBJECT;
		return extension;
	}
}
