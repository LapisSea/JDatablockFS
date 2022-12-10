package com.lapissea.jorth.lang;

import com.lapissea.jorth.EndOfCode;
import com.lapissea.jorth.MalformedJorth;
import com.lapissea.jorth.lang.type.GenericType;

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
			public ClassName readClassName(Function<ClassName, ClassName> imports) throws MalformedJorthException{
				var tok = source.readToken().requireAs(Token.CWord.class);
				tok = new Token.CWord(tok.line(), imports.apply(tok.value()));
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
		};
	}
	
	boolean hasMore();
	
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
		return Token.EWord.find(enumType, t.value());
	}
	default ClassName readClassName(Function<ClassName, ClassName> imports) throws MalformedJorthException{
		return imports.apply(readToken().requireAs(Token.CWord.class).value());
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
			if(!allowArray) throw new MalformedJorth("Array not allowed");
		}
		
		return new GenericType(raw, dims, args);
	}
}
