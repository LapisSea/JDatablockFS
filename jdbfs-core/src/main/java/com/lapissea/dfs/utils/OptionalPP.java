package com.lapissea.dfs.utils;

import com.lapissea.util.function.UnsafeConsumer;
import com.lapissea.util.function.UnsafeFunction;
import com.lapissea.util.function.UnsafeFunctionOL;
import com.lapissea.util.function.UnsafePredicate;
import com.lapissea.util.function.UnsafeSupplier;

import java.io.Serializable;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

public final class OptionalPP<T> implements Serializable{
	private static final OptionalPP<?> EMPTY = new OptionalPP<>(null);
	
	private final T value;
	
	public static <T> OptionalPP<T> empty(){
		@SuppressWarnings("unchecked")
		OptionalPP<T> t = (OptionalPP<T>)EMPTY;
		return t;
	}
	
	private OptionalPP(T value){
		this.value = value;
	}
	
	public static <T> OptionalPP<T> of(T value){
		return new OptionalPP<>(Objects.requireNonNull(value));
	}
	
	public static <T> OptionalPP<T> ofNullable(T value){
		return value == null? empty() : new OptionalPP<>(value);
	}
	
	public T get(){
		if(value == null){
			throw new NoSuchElementException("No value present");
		}
		return value;
	}
	
	public boolean isPresent(){
		return value != null;
	}
	public boolean isPresentAnd(Predicate<T> test){
		return value != null && test.test(value);
	}
	public boolean isEmptyOr(Predicate<T> test){
		return value == null || test.test(value);
	}
	
	public boolean isEmpty(){
		return value == null;
	}
	
	public <E extends Throwable> void ifPresent(UnsafeConsumer<? super T, E> action) throws E{
		if(value != null){
			action.accept(value);
		}
	}
	public <E extends Throwable> OptionalPP<T> filter(UnsafePredicate<? super T, E> predicate) throws E{
		Objects.requireNonNull(predicate);
		if(!isPresent()){
			return this;
		}else{
			return predicate.test(value)? this : empty();
		}
	}
	
	public <K> OptionalPP<Map.Entry<K, T>> asValueWith(K key){
		Objects.requireNonNull(key);
		if(!isPresent()){
			return empty();
		}else{
			return OptionalPP.of(Map.entry(key, this.value));
		}
	}
	public <V> OptionalPP<Map.Entry<T, V>> asKeyWith(V value){
		Objects.requireNonNull(value);
		if(!isPresent()){
			return empty();
		}else{
			return OptionalPP.of(Map.entry(this.value, value));
		}
	}
	public <U, E extends Throwable> OptionalPP<U> map(UnsafeFunction<? super T, ? extends U, E> mapper) throws E{
		Objects.requireNonNull(mapper);
		if(!isPresent()){
			return empty();
		}else{
			return OptionalPP.ofNullable(mapper.apply(value));
		}
	}
	
	public <E extends Throwable> OptionalLong mapToLong(UnsafeFunctionOL<? super T, E> mapper) throws E{
		Objects.requireNonNull(mapper);
		if(!isPresent()){
			return OptionalLong.empty();
		}else{
			return OptionalLong.of(mapper.apply(value));
		}
	}
	
	public <U, E extends Throwable> OptionalPP<U> flatMap(UnsafeFunction<? super T, ? extends OptionalPP<? extends U>, E> mapper) throws E{
		Objects.requireNonNull(mapper);
		if(!isPresent()){
			return empty();
		}else{
			@SuppressWarnings("unchecked")
			OptionalPP<U> r = (OptionalPP<U>)mapper.apply(value);
			return Objects.requireNonNull(r);
		}
	}
	
	public <E extends Throwable> OptionalPP<T> or(UnsafeSupplier<? extends OptionalPP<? extends T>, E> supplier) throws E{
		Objects.requireNonNull(supplier);
		if(isPresent()){
			return this;
		}else{
			@SuppressWarnings("unchecked")
			OptionalPP<T> r = (OptionalPP<T>)supplier.get();
			return Objects.requireNonNull(r);
		}
	}
	
	public Stream<T> stream(){
		if(!isPresent()){
			return Stream.empty();
		}else{
			return Stream.of(value);
		}
	}
	
	public T orElse(T other){
		return value != null? value : other;
	}
	
	public <E extends Throwable> T orElseGet(UnsafeSupplier<? extends T, E> supplier) throws E{
		return value != null? value : supplier.get();
	}
	
	public T orElseThrow(){
		if(value == null){
			throw new NoSuchElementException("No value present");
		}
		return value;
	}
	
	public <X extends Throwable> T orElseThrow(Supplier<? extends X> exceptionSupplier) throws X{
		if(value != null){
			return value;
		}else{
			throw exceptionSupplier.get();
		}
	}
	
	@Override
	public boolean equals(Object obj){
		if(this == obj){
			return true;
		}
		
		return obj instanceof OptionalPP<?> other
		       && Objects.equals(value, other.value);
	}
	
	/**
	 * Returns the hash code of the value, if present, otherwise {@code 0}
	 * (zero) if no value is present.
	 *
	 * @return hash code value of the present value or {@code 0} if no value is
	 * present
	 */
	@Override
	public int hashCode(){
		return Objects.hashCode(value);
	}
	
	/**
	 * Returns a non-empty string representation of this {@code Optional}
	 * suitable for debugging.  The exact presentation format is unspecified and
	 * may vary between implementations and versions.
	 *
	 * @return the string representation of this instance
	 * @implSpec If a value is present, the result must include its string representation
	 * in the result.  Empty and present {@code Optional}s must be unambiguously
	 * differentiable.
	 */
	@Override
	public String toString(){
		return value != null
		       ? ("OptionalPP[" + value + "]")
		       : "OptionalPP.empty";
	}
	
	public Optional<T> opt(){
		return Optional.ofNullable(value);
	}
}
	
