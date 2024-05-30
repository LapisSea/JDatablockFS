package com.lapissea.dfs.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.stream.Collector;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public interface IterablePP<T> extends Iterable<T>{
	
	default Stream<T> stream(){
		return StreamSupport.stream(spliterator(), false);
	}
	
	default boolean isEmpty(){
		return !iterator().hasNext();
	}
	
	default OptionalPP<T> first(){
		var iter = iterator();
		if(iter.hasNext()) return OptionalPP.ofNullable(iter.next());
		return OptionalPP.empty();
	}
	
	default <Accumulator, Result> Result collect(Collector<? super T, Accumulator, Result> collector){
		var acc = collector.supplier().get();
		var add = collector.accumulator();
		for(T t : this){
			add.accept(acc, t);
		}
		return collector.finisher().apply(acc);
	}
	default List<T> collectToList(){
		var res = new ArrayList<T>();
		for(T t : this){
			res.add(t);
		}
		return res;
	}
	
	default Set<T> collectToSet(){
		var res = new HashSet<T>();
		for(T t : this){
			res.add(t);
		}
		return res;
	}
	default <K, V> Map<K, V> collectToMap(Function<T, K> key, Function<T, V> value){
		var res = new HashMap<K, V>();
		for(T t : this){
			var k = key.apply(t);
			if(res.put(k, value.apply(t)) != null){
				throw new IllegalStateException("Duplicate key of: " + k);
			}
		}
		return res;
	}
	
	
	default boolean noneMatch(Predicate<T> predicate){
		return !anyMatch(predicate);
	}
	default boolean anyMatch(Predicate<T> predicate){
		for(T t : this){
			if(predicate.test(t)){
				return true;
			}
		}
		return false;
	}
	default boolean allMatch(Predicate<T> predicate){
		for(T t : this){
			if(!predicate.test(t)){
				return false;
			}
		}
		return true;
	}
	
	default IterablePP<T> filtered(Predicate<T> filter){
		return () -> new Iterator<T>(){
			private final Iterator<T> src = IterablePP.this.iterator();
			
			T next;
			boolean hasData;
			
			void calcNext(){
				while(src.hasNext()){
					T t = src.next();
					if(filter.test(t)){
						next = t;
						hasData = true;
						return;
					}
				}
			}
			
			@Override
			public boolean hasNext(){
				if(!hasData) calcNext();
				return hasData;
			}
			@Override
			public T next(){
				if(!hasData){
					calcNext();
					if(!hasData) throw new NoSuchElementException();
				}
				try{
					return next;
				}finally{
					next = null;
					hasData = false;
				}
			}
		};
	}
	
	default <L> IterablePP<L> flatArray(Function<T, L[]> flatten){
		return flatMap(e -> Arrays.asList(flatten.apply(e)).iterator());
	}
	default <L> IterablePP<L> flatData(Function<T, Iterable<L>> flatten){
		return flatMap(e -> flatten.apply(e).iterator());
	}
	default <L> IterablePP<L> flatMap(Function<T, Iterator<L>> flatten){
		return () -> new Iterator<L>(){
			private final Iterator<T> src = IterablePP.this.iterator();
			
			Iterator<L> flat;
			
			private L next;
			private boolean hasData;
			
			void doNext(){
				while(true){
					if(flat == null || !flat.hasNext()){
						if(!src.hasNext()) return;
						flat = flatten.apply(src.next());
						continue;
					}
					next = flat.next();
					hasData = true;
					break;
				}
			}
			
			@Override
			public boolean hasNext(){
				if(!hasData) doNext();
				return hasData;
			}
			@Override
			public L next(){
				if(!hasData){
					doNext();
					if(!hasData) throw new NoSuchElementException();
				}
				try{
					return next;
				}finally{
					hasData = false;
					next = null;
				}
			}
		};
	}
	
	default <L> IterablePP<L> map(Function<T, L> mapper){
		return () -> new Iterator<>(){
			private final Iterator<T> src = IterablePP.this.iterator();
			
			@Override
			public boolean hasNext(){
				return src.hasNext();
			}
			@Override
			public L next(){
				return mapper.apply(src.next());
			}
		};
	}
	default IterablePP<T> skip(int count){
		var that = this;
		return () -> {
			var iter = that.iterator();
			for(int i = 0; i<count; i++){
				if(!iter.hasNext()) break;
				iter.next();
			}
			return iter;
		};
	}
	
	default IterablePP<T> limit(int maxLen){
		return () -> new Iterator<>(){
			private final Iterator<T> src = IterablePP.this.iterator();
			private int count;
			
			@Override
			public boolean hasNext(){
				return maxLen>count && src.hasNext();
			}
			@Override
			public T next(){
				count++;
				return src.next();
			}
		};
	}
	
	default OptionalPP<T> reduce(BinaryOperator<T> reducer){
		final Iterator<T> src = IterablePP.this.iterator();
		if(!src.hasNext()) return OptionalPP.empty();
		var result = src.next();
		while(src.hasNext()){
			var next = src.next();
			result = reducer.apply(result, next);
		}
		return OptionalPP.of(result);
	}
	
	default OptionalPP<T> min(Comparator<? super T> comparator){
		return reduce(BinaryOperator.minBy(comparator));
	}
	
	default OptionalPP<T> max(Comparator<? super T> comparator){
		return reduce(BinaryOperator.maxBy(comparator));
	}
	
	interface Enumerator<T, R>{
		R enumerate(int index, T value);
	}
	
	interface EnumeratorL<T, R>{
		R enumerate(long index, T value);
	}
	
	record IdxValue<T>(int index, T val) implements Map.Entry<Integer, T>{
		@Override
		public Integer getKey(){ return index; }
		@Override
		public T getValue(){ return val; }
		@Override
		public T setValue(T value){ throw new UnsupportedOperationException(); }
	}
	
	record LdxValue<T>(long index, T val) implements Map.Entry<Long, T>{
		@Override
		public Long getKey(){ return index; }
		@Override
		public T getValue(){ return val; }
		@Override
		public T setValue(T value){ throw new UnsupportedOperationException(); }
	}
	
	default IterablePP<IdxValue<T>> enumerate(){
		return () -> {
			var src = IterablePP.this.iterator();
			return new Iterator<>(){
				private int index;
				@Override
				public boolean hasNext(){
					return src.hasNext();
				}
				@Override
				public IdxValue<T> next(){
					preIncrementInt(index);
					return new IdxValue<>(index++, src.next());
				}
			};
		};
	}
	
	default IterablePP<LdxValue<T>> enumerateL(){
		return () -> {
			var src = IterablePP.this.iterator();
			return new Iterator<>(){
				private long index;
				@Override
				public boolean hasNext(){
					return src.hasNext();
				}
				@Override
				public LdxValue<T> next(){
					preIncrementLong(index);
					return new LdxValue<>(index++, src.next());
				}
			};
		};
	}
	
	default <R> IterablePP<R> enumerate(Enumerator<T, R> enumerator){
		return () -> {
			var src = IterablePP.this.iterator();
			return new Iterator<>(){
				private int index;
				@Override
				public boolean hasNext(){
					return src.hasNext();
				}
				@Override
				public R next(){
					preIncrementInt(index);
					return enumerator.enumerate(index++, src.next());
				}
			};
		};
	}
	default <R> IterablePP<R> enumerateL(EnumeratorL<T, R> enumerator){
		return () -> {
			var src = IterablePP.this.iterator();
			return new Iterator<>(){
				private long index;
				@Override
				public boolean hasNext(){
					return src.hasNext();
				}
				@Override
				public R next(){
					preIncrementLong(index);
					return enumerator.enumerate(index++, src.next());
				}
			};
		};
	}
	
	default <T1> T1[] toArray(IntFunction<T1[]> ctor){
		return collectToList().toArray(ctor);
	}
	default int count(){
		int num = 0;
		for(var ignore : this){
			preIncrementInt(num);
			num++;
		}
		return num;
	}
	
	default long countL(){
		long num = 0;
		for(var ignore : this){
			preIncrementLong(num);
			num++;
		}
		return num;
	}
	
	private static void preIncrementLong(long index){
		if(index == Long.MAX_VALUE) throw new IllegalStateException("Too many elements");
	}
	private static void preIncrementInt(int num){
		if(num == Integer.MAX_VALUE) throw new IllegalStateException("Too many elements");
	}
}
