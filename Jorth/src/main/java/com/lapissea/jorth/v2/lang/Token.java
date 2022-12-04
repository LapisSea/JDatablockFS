package com.lapissea.jorth.v2.lang;

import com.lapissea.jorth.MalformedJorthException;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public sealed interface Token{
	
	record KWord(int line, Keyword keyword) implements Token{
		public void require(Keyword keyword) throws MalformedJorthException{
			if(this.keyword!=keyword) throw new MalformedJorthException("Required keyword is "+keyword+" but got "+this.keyword);
		}
		
		@SuppressWarnings("unchecked")
		@Override
		public <T extends Token> Optional<T> as(Class<T> type){
			if(type==Word.class){
				return Optional.of((T)new Word(line, keyword.name().toLowerCase()));
			}
			return Token.super.as(type);
		}
	}
	
	record EWord<E extends Enum<E>>(int line, E value) implements Token{
		public EWord(int line, Class<E> type, String word) throws MalformedJorthException{
			this(line, find(type, word));
		}
		
		private static final Map<Class<? extends Enum<?>>, Map<String, Enum<?>>> CACHE=new ConcurrentHashMap<>();
		
		@SuppressWarnings("unchecked")
		public static <E extends Enum<E>> E find(Class<E> type, String word) throws MalformedJorthException{
			var map=(Map<String, E>)CACHE.computeIfAbsent(type, t->{
				var values=t.getEnumConstants();
				var m     =HashMap.<String, Enum<?>>newHashMap(values.length);
				for(Enum<?> value : values){
					m.put(value.name().toLowerCase(), value);
				}
				return Map.copyOf(m);
			});
			var e=map.get(word);
			if(e==null) throw new MalformedJorthException("Expected any of "+map.values()+" but got "+word);
			return e;
		}
	}
	
	record Word(int line, String value) implements Token{
		public boolean is(String check){
			return value.equals(check);
		}
		
		@SuppressWarnings("unchecked")
		@Override
		public <T extends Token> Optional<T> as(Class<T> type){
			if(type==CWord.class){
				return Optional.of((T)new CWord(line, ClassName.dotted(value)));
			}
			if(type==SmolWord.class){
				if(value.length()==1){
					return Optional.of((T)new SmolWord(line, value.charAt(0)));
				}else{
					throw new ClassCastException("Can not convert "+this+" to "+SmolWord.class.getName());
				}
			}
			return Token.super.as(type);
		}
	}
	
	record SmolWord(int line, char value) implements Token{
		public boolean is(char check){
			return value==check;
		}
		
		@SuppressWarnings("unchecked")
		@Override
		public <T extends Token> Optional<T> as(Class<T> type){
			if(type==Word.class){
				return Optional.of((T)new Word(line, value+""));
			}
			return Token.super.as(type);
		}
	}
	
	record CWord(int line, ClassName value) implements Token{
		public boolean is(ClassName check){
			return value.equals(check);
		}
		
		@SuppressWarnings("unchecked")
		@Override
		public <T extends Token> Optional<T> as(Class<T> type){
			if(type==Word.class){
				return Optional.of((T)new Word(line, value.dotted()));
			}
			return Token.super.as(type);
		}
	}
	
	record StrValue(int line, String value) implements Token{}
	
	record IntVal(int line, int value) implements Token{}
	
	record FloatVal(int line, float value) implements Token{}
	
	record Null(int line) implements Token{}
	
	record Bool(int line, boolean value) implements Token{}
	
	
	int line();
	
	default <T extends Token> Optional<T> as(Class<T> type){
		if(type.isInstance(this)){
			return Optional.of(type.cast(this));
		}
		return Optional.empty();
	}
	default <T extends Token> T requireAs(Class<T> type) throws MalformedJorthException{
		var o=as(type);
		if(o.isPresent()){
			return o.get();
		}
		throw new MalformedJorthException("Required token type "+type.getSimpleName()+" but got "+this);
	}
}
