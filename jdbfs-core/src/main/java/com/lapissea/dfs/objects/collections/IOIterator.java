package com.lapissea.dfs.objects.collections;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Consumer;
import java.util.stream.Collector;
import java.util.stream.Collectors;

public interface IOIterator<T>{
	interface Iter<T> extends IOIterator<T>, Iterator<T>{
		
		@SuppressWarnings("unchecked")
		static <T> IOIterator.Iter<T> emptyIter(){
			final class Holder{
				private static final IOIterator.Iter<?> EMPTY_ITER = new IOIterator.Iter<>(){
					@Override
					public boolean hasNext(){
						return false;
					}
					@Override
					public Object ioNext(){
						throw new NoSuchElementException();
					}
				};
			}
			return (IOIterator.Iter<T>)Holder.EMPTY_ITER;
		}
		
		@Deprecated
		@Override
		default T next(){
			try{
				return ioNext();
			}catch(IOException e){
				throw new UncheckedIOException(e);
			}
		}
		@Override
		@Deprecated
		default void remove(){
			try{
				ioRemove();
			}catch(IOException e){
				throw new UncheckedIOException(e);
			}
		}
	}
	
	boolean hasNext() throws IOException;
	T ioNext() throws IOException;
	default void ioRemove() throws IOException{
		throw new UnsupportedOperationException(getClass().toString());
	}
	
	default void forRemaining(Consumer<T> el) throws IOException{
		while(hasNext()){
			el.accept(ioNext());
		}
	}
	
	default List<T> toList() throws IOException{
		return collect(Collectors.toUnmodifiableList());
	}
	default <A, R> R collect(Collector<T, A, R> collector) throws IOException{
		var accumulator = collector.supplier().get();
		var accumulate  = collector.accumulator();
		while(hasNext()){
			accumulate.accept(accumulator, ioNext());
		}
		return collector.finisher().apply(accumulator);
	}
}
