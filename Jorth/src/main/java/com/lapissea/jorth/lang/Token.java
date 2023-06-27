package com.lapissea.jorth.lang;

import com.lapissea.jorth.BracketType;
import com.lapissea.jorth.MalformedJorth;
import com.lapissea.jorth.lang.type.KeyedEnum;
import com.lapissea.util.ZeroArrays;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

public sealed interface Token{
	
	record KWord(int line, Keyword keyword) implements Token{
		public void require(Keyword keyword) throws MalformedJorth{
			if(this.keyword != keyword) throw new MalformedJorth("Required keyword is " + keyword + " but got " + this.keyword);
		}
		
		@SuppressWarnings("unchecked")
		@Override
		public <T extends Token> Optional<T> as(Class<T> type){
			if(type == Word.class){
				return Optional.of((T)new Word(line, keyword.name().toLowerCase()));
			}
			return Token.super.as(type);
		}
		@Override
		public <E extends Enum<E>> Optional<E> asEnum(Class<E> clazz){
			return Optional.ofNullable(clazz == Keyword.class? (E)keyword : KeyedEnum.getOptional(clazz, keyword.key()));
		}
	}
	
	record EWord<E extends Enum<E>>(int line, E value) implements Token{
		public EWord(int line, Class<E> type, String word) throws MalformedJorth{
			this(line, KeyedEnum.get(type, word));
		}
		@Override
		public <E extends Enum<E>> Optional<E> asEnum(Class<E> clazz){
			
			return Optional.ofNullable(
				clazz == value.getClass()?
				(E)value :
				KeyedEnum.getOptional(
					clazz,
					value instanceof KeyedEnum e?
					e.key() : value.name().toLowerCase()
				)
			);
		}
	}
	
	record Word(int line, String value) implements Token{
		public boolean is(String check){
			return value.equals(check);
		}
		
		@SuppressWarnings("unchecked")
		@Override
		public <T extends Token> Optional<T> as(Class<T> type){
			if(type == ClassWord.class){
				return Optional.of((T)new ClassWord(line, ClassName.dotted(value)));
			}
			if(type == SmolWord.class){
				if(value.length() == 1){
					return Optional.of((T)new SmolWord(line, value.charAt(0)));
				}else{
					return Optional.empty();
				}
			}
			return Token.super.as(type);
		}
		@Override
		public <E extends Enum<E>> Optional<E> asEnum(Class<E> clazz){
			return Optional.ofNullable(KeyedEnum.getOptional(clazz, value));
		}
	}
	
	record SmolWord(int line, char value) implements Token{
		public boolean is(char check){
			return value == check;
		}
		
		@SuppressWarnings("unchecked")
		@Override
		public <T extends Token> Optional<T> as(Class<T> type){
			if(type == Word.class){
				return Optional.of((T)new Word(line, value + ""));
			}
			if(type == ClassWord.class){
				return Optional.of((T)new ClassWord(line, ClassName.dotted(value + "")));
			}
			return Token.super.as(type);
		}
		@Override
		public <E extends Enum<E>> Optional<E> asEnum(Class<E> clazz){
			return Optional.ofNullable(KeyedEnum.getOptional(clazz, value));
		}
	}
	
	record ClassWord(int line, ClassName value) implements Token{
		public boolean is(ClassName check){
			return value.equals(check);
		}
		
		public ClassWord resolve(Function<ClassName, ClassName> imports){
			var imported = imports.apply(value);
			if(imported.equals(value)) return this;
			return new ClassWord(line, imported);
		}
		
		@SuppressWarnings("unchecked")
		@Override
		public <T extends Token> Optional<T> as(Class<T> type){
			if(type == Word.class){
				return Optional.of((T)new Word(line, value.dotted()));
			}
			return Token.super.as(type);
		}
	}
	
	record Wildcard(int line) implements Token{
		
		public enum BoundType{
			SUPER, EXTENDS
		}
		
		@SuppressWarnings("unchecked")
		@Override
		public <T extends Token> Optional<T> as(Class<T> type){
			if(type == Word.class){
				return Optional.of((T)new Word(line, "?"));
			}
			return Token.super.as(type);
		}
	}
	
	record StrValue(int line, String value) implements Token{ }
	
	sealed interface NumToken extends Token{
		
		record IntVal(int line, int value) implements NumToken{
			@Override
			public Number getNum(){
				return value;
			}
		}
		
		record FloatVal(int line, float value) implements NumToken{
			@Override
			public Number getNum(){
				return value;
			}
		}
		
		Number getNum();
	}
	
	
	record Null(int line) implements Token{ }
	
	record Bool(int line, boolean value) implements Token{ }
	
	record BracketedSet(int line, BracketType type, List<Token> contents) implements Token{
		public Object singleTypeUnpack() throws MalformedJorth{
			if(contents.isEmpty()) return ZeroArrays.ZERO_OBJECT;
			return switch(contents.get(0)){
				case Token.NumToken.IntVal t -> {
					var val = new int[contents.size()];
					for(int i = 0; i<val.length; i++){
						val[i] = ((Token.NumToken.IntVal)contents.get(i)).value();
					}
					yield val;
				}
				case Token.NumToken.FloatVal t -> {
					var val = new float[contents.size()];
					for(int i = 0; i<val.length; i++){
						val[i] = ((Token.NumToken.FloatVal)contents.get(i)).value();
					}
					yield val;
				}
				case Token.StrValue t -> {
					var val = new String[contents.size()];
					for(int i = 0; i<val.length; i++){
						val[i] = ((Token.StrValue)contents.get(i)).value();
					}
					yield val;
				}
				case Token.Bool t -> {
					var val = new boolean[contents.size()];
					for(int i = 0; i<val.length; i++){
						val[i] = ((Token.Bool)contents.get(i)).value();
					}
					yield val;
				}
				default -> throw new MalformedJorth("Illegal token type " + contents.get(0) + " inside bracket block");
			};
		}
	}
	
	
	int line();
	
	default <T extends Token> Optional<T> as(Class<T> type){
		if(type.isInstance(this)){
			return Optional.of(type.cast(this));
		}
		return Optional.empty();
	}
	default <T extends Token> T requireAs(Class<T> type) throws MalformedJorth{
		var o = as(type);
		if(o.isPresent()){
			return o.get();
		}
		throw new MalformedJorth("Required token type " + type.getSimpleName() + " but got " + this);
	}
	
	default <E extends Enum<E>> Optional<E> asEnum(Class<E> clazz){
		return as(Word.class).map(Word::value).map(KeyedEnum.getLookup(clazz)::getOptional);
	}
}
