package com.lapissea.dfs.utils.iterableplus;

import com.lapissea.util.function.UnsafeFunction;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

public sealed interface Match<T>{
	
	@SuppressWarnings("unchecked")
	record None<T>() implements Match<T>{
		private static final None<?> INSTANCE = new None<>();
		@Override
		public T orElse(T defaultValue){ return defaultValue; }
		@Override
		public <U, E extends Throwable> Match<U> map(UnsafeFunction<? super T, ? extends U, E> mapper) throws E{ return (Match<U>)this; }
		@Override
		public Optional<T> opt(){
			return Optional.empty();
		}
		@Override
		public T orElseThrow(){
			throw new NoSuchElementException("No value present");
		}
		@Override
		public boolean isEmpty(){ return true; }
	}
	
	record Some<T>(T val) implements Match<T>{
		public Some{ Objects.requireNonNull(val); }
		@Override
		public T orElse(T defaultValue){ return val; }
		@Override
		public <U, E extends Throwable> Match<U> map(UnsafeFunction<? super T, ? extends U, E> mapper) throws E{
			return ofNullable(mapper.apply(val));
		}
		@Override
		public Optional<T> opt(){ return Optional.of(val); }
		@Override
		public T orElseThrow(){ return val; }
		@Override
		public boolean isEmpty(){ return false; }
	}
	
	static <T> Match<T> empty(){
		//noinspection unchecked
		return (Match<T>)None.INSTANCE;
	}
	static <T> Match<T> of(T val){
		return new Some<>(val);
	}
	static <T> Match<T> from(Optional<T> val){
		if(val.isPresent()){
			return new Some<>(val.get());
		}
		return empty();
	}
	static <T> Match<T> ofNullable(T val){
		if(val != null){
			return new Some<>(val);
		}
		return empty();
	}
	T orElse(T defaultValue);
	
	<U, E extends Throwable> Match<U> map(UnsafeFunction<? super T, ? extends U, E> mapper) throws E;
	
	Optional<T> opt();
	T orElseThrow();
	boolean isEmpty();
	default boolean isPresent(){ return !isEmpty(); }
}
