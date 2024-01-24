package com.lapissea.dfs.utils;

import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.IntFunction;
import java.util.function.LongFunction;
import java.util.function.Supplier;

public final class IterablePPs{
	
	public static <T> IterablePP<T> nullTerminated(Supplier<Supplier<T>> supplier){
		return () -> new Iterator<T>(){
			final Supplier<T> src = supplier.get();
			T next;
			
			void calcNext(){
				next = src.get();
			}
			
			@Override
			public boolean hasNext(){
				if(next == null) calcNext();
				return next != null;
			}
			@Override
			public T next(){
				if(next == null){
					calcNext();
					if(next == null) throw new NoSuchElementException();
				}
				try{
					return next;
				}finally{
					next = null;
				}
			}
		};
	}
	
	private static final IterablePP<?> EMPTY = of0();
	
	public static <T> IterablePP<T> of(){
		return (IterablePP<T>)EMPTY;
	}
	
	@SafeVarargs
	public static <T> IterablePP<T> of(T... data){
		if(data.length == 0) return of();
		return of0(data);
	}
	
	@SuppressWarnings("unchecked")
	private static <T> IterablePP<T> of0(T... data){
		return () -> {
			return new Iterator<>(){
				private int index;
				@Override
				public boolean hasNext(){
					return data.length>index;
				}
				@Override
				public T next(){
					return data[index++];
				}
			};
		};
	}
	public static <T> IterablePP<T> of(Collection<T> data){
		return data::iterator;
	}
	
	private static final class RangeMapLIter<T> implements Iterator<T>{
		private final long            toExclusive;
		private final LongFunction<T> mapping;
		private       long            val;
		
		private RangeMapLIter(long fromInclusive, long toExclusive, LongFunction<T> mapping){
			this.toExclusive = toExclusive;
			this.mapping = mapping;
			val = fromInclusive;
		}
		
		@Override
		public boolean hasNext(){
			return val<toExclusive;
		}
		@Override
		public T next(){
			return mapping.apply(val++);
		}
	}
	
	private static final class RangeMapIIter<T> implements Iterator<T>{
		private final int            toExclusive;
		private final IntFunction<T> mapping;
		private       int            val;
		
		private RangeMapIIter(int fromInclusive, int toExclusive, IntFunction<T> mapping){
			this.toExclusive = toExclusive;
			this.mapping = mapping;
			val = fromInclusive;
		}
		
		@Override
		public boolean hasNext(){
			return val<toExclusive;
		}
		@Override
		public T next(){
			return mapping.apply(val++);
		}
	}
	
	public static <T> IterablePP<T> rangeMap(int fromInclusive, int toExclusive, IntFunction<T> mapping){
		if(fromInclusive>toExclusive) throw new IllegalArgumentException(fromInclusive + " > " + toExclusive);
		return () -> new RangeMapIIter<>(fromInclusive, toExclusive, mapping);
	}
	
	public static <T> IterablePP<T> rangeMap(long fromInclusive, long toExclusive, LongFunction<T> mapping){
		if(fromInclusive>toExclusive) throw new IllegalArgumentException(fromInclusive + " > " + toExclusive);
		return () -> new RangeMapLIter<>(fromInclusive, toExclusive, mapping);
	}
	
	@SafeVarargs
	@SuppressWarnings("varargs")
	public static <T> IterablePP<T> concat(Iterable<T>... iterables){
		return of(iterables).flatMap(Iterable::iterator);
	}
}
