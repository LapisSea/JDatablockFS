package com.lapissea.jorth.lang;

import com.lapissea.jorth.BracketType;
import com.lapissea.jorth.EndOfCode;
import com.lapissea.jorth.MalformedJorth;
import com.lapissea.jorth.lang.type.GenericType;
import com.lapissea.jorth.lang.type.JType;
import com.lapissea.jorth.lang.type.KeyedEnum;
import com.lapissea.util.function.UnsafeSupplier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public interface TokenSource{
	
	
	static TokenSource of(UnsafeSupplier<Token, MalformedJorth> tokenStream){
		return new TokenSource(){
			private MalformedJorth e;
			private Token next;
			
			private void readNext(){
				if(next != null) return;
				try{
					next = tokenStream.get();
				}catch(MalformedJorth e){
					this.e = e;
				}
			}
			
			@Override
			public boolean hasMore(){
				readNext();
				return !(e instanceof EndOfCode);
			}
			@Override
			public Token readToken(boolean required) throws MalformedJorth{
				readNext();
				if(e != null){
					if(!required && e instanceof EndOfCode){
						e = null;
						return null;
					}
					throw e;
				}
				var n = next;
				next = null;
				return n;
			}
			@Override
			public Token peekToken(boolean required) throws MalformedJorth{
				readNext();
				if(e != null){
					if(!required && e instanceof EndOfCode){
						e = null;
						return null;
					}
					throw e;
				}
				return next;
			}
			@Override
			public int line(){
				readNext();
				return next != null? next.line() : -1;
			}
		};
	}
	
	static TokenSource listen(TokenSource source, Consumer<Token> listener){
		return new TokenSource(){
			@Override
			public boolean hasMore(){
				return source.hasMore();
			}
			
			@Override
			public Token peekToken(boolean required) throws MalformedJorth{
				return source.peekToken(required);
			}
			
			@Override
			public Token readToken(boolean required) throws MalformedJorth{
				var tok = source.readToken(required);
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
			public Token.BracketedSet bracketSet(char... allowed) throws MalformedJorth{
				var t = source.bracketSet(allowed);
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
	default <T extends Token> boolean consumeTokenIf(Class<T> type) throws MalformedJorth{
		return consumeTokenIf(type, ignored -> true);
	}
	default <T extends Token> boolean consumeTokenIf(Class<T> type, Predicate<T> predicate) throws MalformedJorth{
		return consumeTokenIf(t -> t.as(type).filter(predicate)).isPresent();
	}
	
	default <T> Optional<T> consumeTokenIf(Function<Token, Optional<T>> predicate) throws MalformedJorth{
		Token peek = peekToken(false);
		if(peek == null) return Optional.empty();
		
		var mapped = predicate.apply(peek);
		if(mapped.isPresent()){
			readToken();
		}
		return mapped;
	}
	default Token readToken() throws MalformedJorth{
		return readToken(true);
	}
	default Token peekToken() throws MalformedJorth{
		return peekToken(true);
	}
	
	Token readToken(boolean required) throws MalformedJorth;
	Token peekToken(boolean required) throws MalformedJorth;
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
	
	default int readChar() throws MalformedJorth{
		return readToken().requireAs(Token.SmolWord.class).value();
	}
	default int readInt() throws MalformedJorth{
		return readToken().requireAs(Token.NumToken.IntVal.class).value();
	}
	default float readFloat() throws MalformedJorth{
		return readToken().requireAs(Token.NumToken.FloatVal.class).value();
	}
	default boolean readBool() throws MalformedJorth{
		return readToken().requireAs(Token.Bool.class).value();
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
	default <E extends Enum<E>> Optional<E> readEnumOptional(Class<E> enumType) throws MalformedJorth{
		return consumeTokenIf(
			t -> t.as(Token.Word.class).map(Token.Word::value)
			      .map(w -> KeyedEnum.getOptional(enumType, w))
		);
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
	default JType readType(Function<ClassName, ClassName> imports) throws MalformedJorth{
		return readType(imports, true, true);
	}
	
	default GenericType readTypeSimple(Function<ClassName, ClassName> imports) throws MalformedJorth{
		return (GenericType)readType(imports, false, false);
	}
	
	default JType readType(Function<ClassName, ClassName> imports, boolean allowArray, boolean allowWildcard) throws MalformedJorth{
		if(allowWildcard && consumeTokenIf(Token.Wildcard.class)){
			var typeO = readEnumOptional(Token.Wildcard.BoundType.class);
			if(typeO.isEmpty()) return new JType.Wildcard(List.of(), List.of());
			var type   = typeO.get();
			var bounds = new ArrayList<JType>();
			if(consumeTokenIfIsText('[')){
				while(!consumeTokenIfIsText(']')){
					bounds.add(readType(imports, allowArray, allowWildcard));
				}
			}else{
				bounds.add(readType(imports, allowArray, allowWildcard));
			}
			List<JType> lower = List.of(), upper = List.of(GenericType.OBJECT);
			switch(type){
				case SUPER -> lower = bounds;
				case EXTENDS -> upper = bounds;
			}
			return new JType.Wildcard(lower, upper);
		}
		
		var raw = readClassName(imports);
		
		var args = new ArrayList<JType>();
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
	
	default Token.BracketedSet bracketSet(char... allowed) throws MalformedJorth{
		var token   = readToken();
		var bracket = token.as(Token.SmolWord.class).map(c -> BracketType.byOpen(c.value())).filter(Optional::isPresent).map(Optional::get);
		if(bracket.isEmpty()){
			var options = allowed.length == 0
			              ? Arrays.stream(BracketType.values()).map(c -> c.openStr).collect(Collectors.joining(", "))
			              : allowed;
			throw new MalformedJorth("Expected any of " + options + " but got " + token);
		}
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
			if(peekToken().as(Token.SmolWord.class).filter(s -> s.is(type.open)).isPresent()){
				contents.add(bracketSet(allowed));
				continue;
			}
			contents.add(readToken());
		}
		return new Token.BracketedSet(token.line(), type, List.copyOf(contents));
	}
}
