package com.lapissea.jorth.lang;

import com.lapissea.jorth.MalformedJorth;
import com.lapissea.jorth.lang.type.KeyedEnum;

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
