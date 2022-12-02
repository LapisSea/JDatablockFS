package com.lapissea.jorth.v2.lang;

import com.lapissea.jorth.MalformedJorthException;
import com.lapissea.jorth.v2.lang.type.GenericType;

import java.util.ArrayList;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

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
				var tok=source.readToken();
				listener.accept(tok);
				return tok;
			}
			
			@Override
			public void addDefinition(String value, String key){
				source.addDefinition(value, key);
			}
			@Override
			public <E extends Enum<E>> Token.EWord<E> readEnum(Class<E> visibilityClass) throws MalformedJorthException{
				var t=source.readToken().requireAs(Token.Word.class);
				var e=new Token.EWord<>(t.line(), visibilityClass, t.value());
				listener.accept(e);
				return e;
			}
		};
	}
	
	boolean hasMore();
	
	default <T extends Token> Optional<T> readToken(Function<Token, Optional<T>> predicate) throws MalformedJorthException{
		var mapped=predicate.apply(peekToken());
		if(mapped.isPresent()){
			readToken();
		}
		return mapped;
	}
	
	Token readToken() throws MalformedJorthException;
	Token peekToken() throws MalformedJorthException;
	
	default String readWord() throws MalformedJorthException{
		return readToken().requireAs(Token.Word.class).value();
	}
	default Keyword readKeyword() throws MalformedJorthException{
		return readToken().requireAs(Token.KWord.class).keyword();
	}
	default void requireKeyword(Keyword kw) throws MalformedJorthException{
		readToken().requireAs(Token.KWord.class).require(kw);
	}
	default <E extends Enum<E>> Token.EWord<E> readEnum(Class<E> visibilityClass) throws MalformedJorthException{
		var t=readToken().requireAs(Token.Word.class);
		return new Token.EWord<>(t.line(), visibilityClass, t.value());
	}
	
	
	// raw: foo.Bar
	// array: foo.Bar array
	// generic: foo.Bar<ay.Lmao, idk.Something>
	// generic array: foo.Bar<ay.Lmao idk.Something> array
	default GenericType readType() throws MalformedJorthException{
		return readType(true);
	}
	default GenericType readType(boolean allowArray) throws MalformedJorthException{
		var raw =ClassName.dotted(readWord());
		int dims=0;
		var args=new ArrayList<GenericType>();
		
		if(peekToken() instanceof Token.Word w){
			if(w.value().equals("<")){
				readToken();
				
				while(true){
					args.add(readType());
					
					if(peekToken() instanceof Token.Word w2){
						if(w2.value().equals(">")){
							readToken();
							break;
						}
					}
				}
				
			}
		}
		
		while(true){
			if(peekToken() instanceof Token.Word w){
				if(w.value().equals("array")){
					dims++;
					readToken();
					if(!allowArray) throw new MalformedJorthException("Array not allowed");
					continue;
				}
			}
			break;
		}
		
		return new GenericType(raw, dims, args);
	}
	
	void addDefinition(String value, String key);
}
