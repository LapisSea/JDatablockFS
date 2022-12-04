package com.lapissea.jorth.v2.lang;

import com.lapissea.jorth.MalformedJorthException;
import com.lapissea.jorth.v2.lang.type.GenericType;

import java.util.ArrayList;
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
			public Token peekToken() throws MalformedJorthException{
				return source.peekToken();
			}
			
			@Override
			public Token readToken() throws MalformedJorthException{
				var tok = source.readToken();
				listener.accept(tok);
				return tok;
			}
			@Override
			public ClassName readClassName(Function<ClassName, ClassName> imports) throws MalformedJorthException{
				var tok = source.readToken().requireAs(Token.CWord.class);
				listener.accept(tok);
				return imports.apply(tok.value());
			}
			@Override
			public int line(){
				return source.line();
			}
			@Override
			public <E extends Enum<E>> E readEnum(Class<E> enumType) throws MalformedJorthException{
				var t = source.readToken().requireAs(Token.Word.class);
				var e = new Token.EWord<>(t.line(), enumType, t.value());
				listener.accept(e);
				return e.value();
			}
		};
	}
	
	boolean hasMore();
	
	default <T extends Token> boolean consumeTokenIf(Class<T> type, Predicate<T> predicate) throws MalformedJorthException{
		return consumeTokenIf(t -> t.as(type).filter(predicate)).isPresent();
	}
	
	default <T> Optional<T> consumeTokenIf(Function<Token, Optional<T>> predicate) throws MalformedJorthException{
		var mapped = predicate.apply(peekToken());
		if(mapped.isPresent()){
			readToken();
		}
		return mapped;
	}
	
	Token readToken() throws MalformedJorthException;
	Token peekToken() throws MalformedJorthException;
	int line();
	
	default void requireWord(String word) throws MalformedJorthException{
		var token = readToken();
		if(word.length() == 1 && token instanceof Token.SmolWord w){
			if(w.is(word.charAt(0))) return;
			throw new MalformedJorthException("Expected '" + word + "' but got '" + w.value() + "'");
		}else{
			var w = token.requireAs(Token.Word.class);
			if(w.is(word)) return;
			throw new MalformedJorthException("Expected '" + word + "' but got '" + w.value() + "'");
		}
	}
	
	default String readWord() throws MalformedJorthException{
		return readToken().requireAs(Token.Word.class).value();
	}
	default Keyword readKeyword() throws MalformedJorthException{
		return readToken().requireAs(Token.KWord.class).keyword();
	}
	default void requireKeyword(Keyword kw) throws MalformedJorthException{
		readToken().requireAs(Token.KWord.class).require(kw);
	}
	default <E extends Enum<E>> E readEnum(Class<E> enumType) throws MalformedJorthException{
		var t = readToken().requireAs(Token.Word.class);
		return Token.EWord.find(enumType, t.value());
	}
	default ClassName readClassName(Function<ClassName, ClassName> imports) throws MalformedJorthException{
		return imports.apply(readToken().requireAs(Token.CWord.class).value());
	}
	
	
	// raw: foo.Bar
	// array: foo.Bar array
	// generic: foo.Bar<ay.Lmao idk.Something>
	// generic array: foo.Bar<ay.Lmao idk.Something> array
	default GenericType readType(Function<ClassName, ClassName> imports) throws MalformedJorthException{
		return readType(imports, true);
	}
	default GenericType readType(Function<ClassName, ClassName> imports, boolean allowArray) throws MalformedJorthException{
		var raw = readClassName(imports);
		
		var args = new ArrayList<GenericType>();
		if(consumeTokenIf(Token.SmolWord.class, w -> w.is('<'))){
			while(true){
				args.add(readType(imports));
				
				if(consumeTokenIf(Token.SmolWord.class, w -> w.is('>'))){
					break;
				}
			}
		}
		
		int dims = 0;
		while(consumeTokenIf(Token.Word.class, w -> w.is("array"))){
			dims++;
			if(!allowArray) throw new MalformedJorthException("Array not allowed");
		}
		
		return new GenericType(raw, dims, args);
	}
}
