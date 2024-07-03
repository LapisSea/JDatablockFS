package com.lapissea.dfs.utils;

import com.lapissea.dfs.utils.function.FunctionOL;
import com.lapissea.util.function.UnsafePredicate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.stream.Collector;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * An expanded version of {@link Iterable} that replaces use of {@link Stream}
 * for simple/common use cases. It differs from the Stream api in the following ways:
 * <ul>
 *   <li>
 *       It is a reusable computation. Aka, you can compute the result multiple times.
 *       It is less of a data processor and more of a data view or transformer.
 *   </li>
 *   <li>
 *       It has very low overhead. While it does create lambda objects, they are very
 *       simple and most of the time will get inlined by the JIT.
 *   </li>
 * </ul>
 */
public interface IterablePP<T> extends Iterable<T>{
	
	default Stream<T> stream(){
		return StreamSupport.stream(spliterator(), false);
	}
	
	default boolean isEmpty(){
		return !iterator().hasNext();
	}
	
	default T getFirst(){
		var iter = iterator();
		if(iter.hasNext()) return iter.next();
		throw new NoSuchElementException();
	}
	
	default <E extends Throwable> OptionalPP<T> firstMatching(UnsafePredicate<T, E> predicate) throws E{
		for(T t : this){
			if(t != null && predicate.test(t)){
				return OptionalPP.of(t);
			}
		}
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
	
	private ArrayList<T> toArrayList(){
		var res = new ArrayList<T>();
		for(T t : this){
			res.add(t);
		}
		return res;
	}
	default List<T> collectToList(){
		return toArrayList();
	}
	
	default Set<T> collectToSet(){
		var res = new HashSet<T>();
		for(T t : this){
			res.add(t);
		}
		return res;
	}
	
	default <K, V> Map<K, V> collectUnmodifiableToMap(boolean counting, Function<T, K> key, Function<T, V> value){
		if(counting){
			var arr = new Map.Entry[count()];
			int i   = 0;
			for(T t : this){
				arr[i++] = Map.entry(key.apply(t), value.apply(t));
			}
			//noinspection unchecked
			return Map.ofEntries(arr);
		}
		
		var res = new ArrayList<Map.Entry<K, V>>();
		for(T t : this){
			res.add(Map.entry(key.apply(t), value.apply(t)));
		}
		//noinspection unchecked
		return Map.ofEntries(res.toArray(Map.Entry[]::new));
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
	
	
	default IterablePPs.PPCollection<T> asCollection(){
		return new IterablePPs.PPCollection<>(this);
	}
	
	default String joinAsStrings()                      { return joinAsStrings(""); }
	default String joinAsStrings(CharSequence delimiter){ return joinAsStrings(delimiter, "", ""); }
	default String joinAsStrings(CharSequence delimiter, CharSequence prefix, CharSequence suffix){
		var res = new StringJoiner(delimiter, prefix, suffix);
		for(T t : this){
			res.add(Objects.toString(t));
		}
		return res.toString();
	}
	
	default <E extends Throwable> boolean noneMatch(UnsafePredicate<T, E> predicate) throws E{
		return !anyMatch(predicate);
	}
	default <E extends Throwable> boolean anyMatch(UnsafePredicate<T, E> predicate) throws E{
		for(T t : this){
			if(predicate.test(t)){
				return true;
			}
		}
		return false;
	}
	default <E extends Throwable> boolean allMatch(UnsafePredicate<T, E> predicate) throws E{
		for(T t : this){
			if(!predicate.test(t)){
				return false;
			}
		}
		return true;
	}
	
	default IterablePP<T> filtered(Predicate<T> filter){
		return () -> {
			var src = IterablePP.this.iterator();
			return new Iterator<T>(){
				
				T       next;
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
		};
	}
	
	default <U extends Comparable<? super U>> IterablePP<T> sortedBy(Function<T, U> comparator){
		return sorted(Comparator.comparing(comparator));
	}
	default IterablePP<T> sorted(Comparator<T> comparator){
		return new IterablePP<>(){
			private ArrayList<T> sorted;
			@Override
			public Iterator<T> iterator(){
				var l = sorted;
				if(l == null){
					l = IterablePP.this.toArrayList();
					l.sort(comparator);
					sorted = l;
				}
				return l.iterator();
			}
		};
	}
	
	default IterablePP<T> distinct(){
		return () -> IterablePP.this.filtered(new HashSet<T>()::add).iterator();
	}
	
	default <L> IterablePP<L> flatArray(Function<T, L[]> flatten){
		return flatMap(e -> Arrays.asList(flatten.apply(e)).iterator());
	}
	default <L> IterablePP<L> flatData(Function<T, Iterable<L>> flatten){
		return flatMap(e -> flatten.apply(e).iterator());
	}
	default <L> IterablePP<L> flatMap(Function<T, Iterator<L>> flatten){
		return () -> {
			var src = IterablePP.this.iterator();
			return new Iterator<L>(){
				
				Iterator<L> flat;
				
				private L       next;
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
		};
	}
	
	default <L> IterablePP<L> map(Function<T, L> mapper){
		return () -> {
			var src = IterablePP.this.iterator();
			return new Iterator<>(){
				
				@Override
				public boolean hasNext(){
					return src.hasNext();
				}
				@Override
				public L next(){
					return mapper.apply(src.next());
				}
			};
		};
	}
	
	default IterableLongPP mapToLong(FunctionOL<T> mapper){
		return () -> {
			var iter = IterablePP.this.iterator();
			return new LongIterator(){
				@Override
				public boolean hasNext(){
					return iter.hasNext();
				}
				@Override
				public long nextLong(){
					return mapper.apply(iter.next());
				}
			};
		};
	}
	
	default IterablePP<T> skip(int count){
		if(count<0) throw new IllegalArgumentException("count cannot be negative");
		return () -> {
			var iter = IterablePP.this.iterator();
			for(int i = 0; i<count; i++){
				if(!iter.hasNext()) break;
				iter.next();
			}
			return iter;
		};
	}
	
	default IterablePP<T> limit(int maxLen){
		if(maxLen<0) throw new IllegalArgumentException("maxLen cannot be negative");
		return () -> {
			var src = IterablePP.this.iterator();
			return new Iterator<>(){
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
