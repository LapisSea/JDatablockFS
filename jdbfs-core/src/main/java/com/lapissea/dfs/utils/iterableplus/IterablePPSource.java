package com.lapissea.dfs.utils.iterableplus;

import com.lapissea.dfs.utils.function.FunctionOI;
import com.lapissea.dfs.utils.function.FunctionOL;
import com.lapissea.util.function.UnsafePredicate;

import java.util.Collection;
import java.util.Iterator;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Function;
import java.util.function.Predicate;

@SuppressWarnings("unused")
public interface IterablePPSource<T> extends Iterable<T>{
	
	OptionalInt tryGetSize();
	
	default IterablePP.SizedPP<T> iter(){
		if(this instanceof Collection){
			//noinspection unchecked
			return Iters.from((Collection<T>)this);
		}
		return new IterablePP.SizedPP<>(){
			@Override
			public OptionalInt getSize(){
				return IterablePPSource.this.tryGetSize();
			}
			@Override
			public Iterator<T> iterator(){
				return IterablePPSource.this.iterator();
			}
		};
	}
	
	default <L> IterablePP<L> instancesOf(Class<L> type){
		return iter().instancesOf(type);
	}
	default IterablePP<T> instancesOf(Class<?> type, Function<T, ?> element){
		return iter().instancesOf(type, element);
	}
	default IterablePP<T> filtered(Predicate<T> filter){
		return iter().filter(filter);
	}
	
	default <L> IterablePP<L> flatOptionals(Function<T, Optional<L>> map){
		return iter().flatOptionals(map);
	}
	
	default <L> IterablePP<L> mapped(Function<T, L> mapper){
		return iter().map(mapper);
	}
	default <L> IterablePP<L> flatMapped(Function<T, Iterable<L>> mapper){
		return iter().flatMap(mapper);
	}
	default IterableLongPP mappedToLong()                    { return iter().mapToLong(); }
	default IterableLongPP mappedToLong(FunctionOL<T> mapper){ return iter().mapToLong(mapper); }
	default IterableIntPP mappedToInt()                      { return iter().mapToInt(); }
	default IterableIntPP mappedToInt(FunctionOI<T> mapper)  { return iter().mapToInt(mapper); }
	
	default <E extends Throwable> boolean noneMatches(UnsafePredicate<T, E> predicate) throws E{
		return iter().noneMatch(predicate);
	}
	default <E extends Throwable> boolean anyMatches(UnsafePredicate<T, E> predicate) throws E{
		return iter().anyMatch(predicate);
	}
	default <E extends Throwable> boolean allMatches(UnsafePredicate<T, E> predicate) throws E{
		return iter().allMatch(predicate);
	}
	
	default boolean noneIs(T value)    { return iter().noneIs(value); }
	default boolean anyIs(T value)     { return iter().anyIs(value); }
	default boolean noneEquals(T value){ return iter().noneEquals(value); }
	default boolean anyEquals(T value) { return iter().anyEquals(value); }
}
