package com.lapissea.dfs.utils.iterableplus;

import com.lapissea.dfs.utils.function.FunctionOI;
import com.lapissea.dfs.utils.function.FunctionOL;

import java.util.Iterator;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Function;
import java.util.function.Predicate;

public interface IterablePPSource<T> extends Iterable<T>{
	
	OptionalInt tryGetSize();
	
	default IterablePP.SizedPP<T> iter(){
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
}
