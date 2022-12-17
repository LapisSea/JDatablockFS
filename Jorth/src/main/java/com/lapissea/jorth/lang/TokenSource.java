package com.lapissea.jorth.lang;

import com.lapissea.jorth.BracketType;
import com.lapissea.jorth.EndOfCode;
import com.lapissea.jorth.MalformedJorth;
import com.lapissea.jorth.lang.type.GenericType;
import com.lapissea.jorth.lang.type.KeyedEnum;
import com.lapissea.util.ZeroArrays;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

public interface TokenSource{
	
	static TokenSource listen(TokenSource source, Consumer<Token> listener){
		return new TokenSource(){
			@Override
			public boolean hasMore(){
				return source.hasMore();
			}
			
			@Override
			public Token peekToken() throws MalformedJorth{
				return source.peekToken();
			}
			
			@Override
			public Token readToken() throws MalformedJorth{
				var tok = source.readToken();
				listener.accept(tok);
				return tok;
			}
			@Override
			public ClassName readClassName(Function<ClassName, ClassName> imports) throws MalformedJorth{
				var tok = source.readToken().requireAs(Token.ClassWord.class).resolve(imports);
				listener.accept(tok);
				return imports.apply(tok.value());
			}
			@Override
			public int line(){
				return source.line();
			}
			@Override
			public <E extends Enum<E>> E readEnum(Class<E> enumType) throws MalformedJorth{
				var t = source.readToken().requireAs(Token.Word.class);
				var e = new Token.EWord<>(t.line(), enumType, t.value());
				listener.accept(e);
				return e.value();
			}
			@Override
			public Token readTokenOrBracketSet(boolean allowEmpty, char... allowed) throws MalformedJorth{
				var t = source.readTokenOrBracketSet(allowEmpty, allowed);
				t.as(Token.BracketedSet.class).ifPresentOrElse(b -> {
					listener.accept(new Token.SmolWord(b.line(), b.type().open));
					var c = b.contents();
					for(var part : c){
						listener.accept(part);
					}
					listener.accept(new Token.SmolWord(c.isEmpty()? b.line() : c.get(c.size() - 1).line(), b.type().close));
				}, () -> listener.accept(t));
				return t;
			}
		};
	}
	
	boolean hasMore();
	
	default boolean consumeTokenIfIsText(char c) throws MalformedJorth{
		return consumeTokenIf(Token.SmolWord.class, w -> w.is(c));
	}
	default boolean consumeTokenIfIsText(String text) throws MalformedJorth{
		return consumeTokenIf(Token.Word.class, w -> w.is(text));
	}
	default <T extends Token> boolean consumeTokenIf(Class<T> type, Predicate<T> predicate) throws MalformedJorth{
		return consumeTokenIf(t -> t.as(type).filter(predicate)).isPresent();
	}
	
	default <T> Optional<T> consumeTokenIf(Function<Token, Optional<T>> predicate) throws MalformedJorth{
		Token peek;
		try{
			peek = peekToken();
		}catch(EndOfCode e){
			return Optional.empty();
		}
		var mapped = predicate.apply(peek);
		if(mapped.isPresent()){
			readToken();
		}
		return mapped;
	}
	
	Token readToken() throws MalformedJorth;
	Token peekToken() throws MalformedJorth;
	int line();
	
	default void requireWord(String word) throws MalformedJorth{
		var token = readToken();
		if(word.length() == 1 && token instanceof Token.SmolWord w){
			if(w.is(word.charAt(0))) return;
			throw new MalformedJorth("Expected '" + word + "' but got '" + w.value() + "'");
		}else{
			var w = token.requireAs(Token.Word.class);
			if(w.is(word)) return;
			throw new MalformedJorth("Expected '" + word + "' but got '" + w.value() + "'");
		}
	}
	
	default String readWord() throws MalformedJorth{
		return readToken().requireAs(Token.Word.class).value();
	}
	default Keyword readKeyword() throws MalformedJorth{
		return readToken().requireAs(Token.KWord.class).keyword();
	}
	default void requireKeyword(Keyword kw) throws MalformedJorth{
		readToken().requireAs(Token.KWord.class).require(kw);
	}
	default <E extends Enum<E>> E readEnum(Class<E> enumType) throws MalformedJorth{
		var t = readToken().requireAs(Token.Word.class);
		return KeyedEnum.get(enumType, t.value());
	}
	default ClassName readClassName(Function<ClassName, ClassName> imports) throws MalformedJorth{
		return imports.apply(readToken().requireAs(Token.ClassWord.class).value());
	}
	
	
	// raw: foo.Bar
	// array: foo.Bar array
	// generic: foo.Bar<ay.Lmao idk.Something>
	// generic array: foo.Bar<ay.Lmao idk.Something> array
	default GenericType readType(Function<ClassName, ClassName> imports) throws MalformedJorth{
		return readType(imports, true);
	}
	default GenericType readType(Function<ClassName, ClassName> imports, boolean allowArray) throws MalformedJorth{
		var raw = readClassName(imports);
		
		var args = new ArrayList<GenericType>();
		if(consumeTokenIfIsText('<')){
			do{
				args.add(readType(imports));
			}while(!consumeTokenIfIsText('>'));
		}
		
		int dims = 0;
		while(consumeTokenIfIsText("array")){
			dims++;
			if(!allowArray) throw new MalformedJorth("Array not allowed");
		}
		
		return new GenericType(raw, dims, args);
	}
	
	default Token readTokenOrBracketSet(boolean allowEmpty) throws MalformedJorth{
		return readTokenOrBracketSet(allowEmpty, ZeroArrays.ZERO_CHAR);
	}
	default Token readTokenOrBracketSet(boolean allowEmpty, char... allowed) throws MalformedJorth{
		var token   = readToken();
		var bracket = token.as(Token.SmolWord.class).map(c -> BracketType.byOpen(c.value())).filter(Optional::isPresent).map(Optional::get);
		
		if(bracket.isEmpty()) return token;
		var type = bracket.get();
		
		find:
		if(allowed.length>0){
			for(char c : allowed){
				if(c == type.open) break find;
			}
			throw new MalformedJorth(type.openStr + type.closeStr + " is not allowed here");
		}
		
		var contents = new ArrayList<Token>();
		
		while(!consumeTokenIfIsText(type.close)){
			contents.add(readToken());
		}
		if(!allowEmpty && contents.isEmpty()){
			throw new MalformedJorth("Empty " + type.openStr + type.closeStr + " is not allowed");
		}
		return new Token.BracketedSet(token.line(), type, List.copyOf(contents));
	}
}
