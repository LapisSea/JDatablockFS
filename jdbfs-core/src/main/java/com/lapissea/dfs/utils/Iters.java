package com.lapissea.dfs.utils;

import com.lapissea.util.TextUtil;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.IntFunction;
import java.util.function.LongFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

public final class Iters{
	
	abstract static class FindingIter<T> implements Iterator<T>{
		
		private T       next;
		private boolean hasData;
		
		protected void reportFound(T val){
			hasData = true;
			next = val;
		}
		
		protected abstract boolean doNext();
		
		@Override
		public boolean hasNext(){
			return hasData || doNext();
		}
		@Override
		public T next(){
			if(!hasData && !doNext()) throw new NoSuchElementException();
			try{
				return next;
			}finally{
				hasData = false;
				next = null;
			}
		}
	}
	
	abstract static class FindingNonNullIter<T> implements Iterator<T>{
		
		private T next;
		
		protected abstract T doNext();
		
		@Override
		public boolean hasNext(){
			return next != null || (next = doNext()) != null;
		}
		@Override
		public T next(){
			var n = next;
			if(n != null){
				next = null;
				return n;
			}
			n = doNext();
			if(n == null) throw new NoSuchElementException();
			return n;
		}
	}
	
	private static class ArrayIter<T> implements Iterator<T>{
		private final T[] data;
		private       int index;
		public ArrayIter(T[] data){ this.data = data; }
		@Override
		public boolean hasNext(){ return data.length>index; }
		@Override
		public T next(){ return data[index++]; }
	}
	
	private record ColIterable<T>(Collection<T> data) implements IterablePP<T>{
		@Override
		public Iterator<T> iterator(){
			return data.iterator();
		}
	}
	
	private record ArrIterable<T>(T[] data) implements IterablePP<T>{
		@Override
		public Iterator<T> iterator(){
			return new ArrayIter<>(data);
		}
	}
	
	static{ TextUtil.CUSTOM_TO_STRINGS.register(IterablePP.class, IterablePP::toString); }
	
	public static <T> IterablePP<T> iterate(T seed, Predicate<? super T> hasNext, UnaryOperator<T> next){
		Objects.requireNonNull(next);
		Objects.requireNonNull(hasNext);
		return () -> new Iterator<>(){
			private T       val = seed;
			private boolean advance;
			
			private void advance(){
				val = next.apply(val);
				advance = false;
			}
			@Override
			public boolean hasNext(){
				if(advance) advance();
				return hasNext.test(val);
			}
			@Override
			public T next(){
				var v = val;
				if(advance) advance();
				advance = true;
				return v;
			}
		};
	}
	
	public static String toString(IterablePP<?> inst){
		return inst.joinAsStr(", ", "[", "]", TextUtil::toShortString);
	}
	
	public static <T> IterablePP<T> nullTerminated(Supplier<Supplier<T>> supplier){
		return () -> {
			var src = supplier.get();
			return new FindingNonNullIter<>(){
				@Override
				protected T doNext(){
					return src.get();
				}
			};
		};
	}
	
	static final Iterator<Object>   EMPTY_ITER = new Iterator<>(){
		@Override
		public boolean hasNext(){ return false; }
		@Override
		public Object next(){ return null; }
	};
	static final IterablePP<Object> EMPTY      = () -> (Iterator<Object>)EMPTY_ITER;
	
	@SuppressWarnings("unchecked")
	public static <T> IterablePP<T> of(){ return (IterablePP<T>)EMPTY; }
	
	public static IterableLongPP ofLongs(long... data){
		if(data.length == 0) return IterableLongPP.empty();
		return () -> new IterableLongPP.ArrayIterL(data);
	}
	public static IterableIntPP ofInts(int... data){
		if(data.length == 0) return IterableIntPP.empty();
		return () -> new IterableIntPP.ArrayIterI(data);
	}
	
	@SafeVarargs
	public static <T> IterablePP<T> of(T... data){
		if(data.length == 0) return of();
		return new ArrIterable<>(data);
	}
	
	public static <T> IterablePP<T> from(Collection<T> data)                { return new ColIterable<>(data); }
	public static <V> IterablePP<V> values(Map<?, V> data)                  { return new ColIterable<>(data.values()); }
	public static <K> IterablePP<K> keys(Map<K, ?> data)                    { return new ColIterable<>(data.keySet()); }
	public static <K, V> IterablePP<Map.Entry<K, V>> entries(Map<K, V> data){ return new ColIterable<>(data.entrySet()); }
	
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
	
	public static <T> IterablePP<T> rangeMapL(long fromInclusive, long toExclusive, LongFunction<T> mapping){
		if(fromInclusive>toExclusive) throw new IllegalArgumentException(fromInclusive + " > " + toExclusive);
		return () -> new RangeMapLIter<>(fromInclusive, toExclusive, mapping);
	}
	
	@SafeVarargs
	@SuppressWarnings("varargs")
	public static <T> IterablePP<T> concat(Iterable<T>... iterables){
		return of(iterables).flatMap(Iterable::iterator);
	}
	public static <T> IterablePP<T> concat1N(T first, Iterable<T> extra){
		return concat(Iters.of(first), extra);
	}
	public static <T> IterablePP<T> concatN1(Iterable<T> start, T last){
		return concat(start, Iters.of(last));
	}
}
