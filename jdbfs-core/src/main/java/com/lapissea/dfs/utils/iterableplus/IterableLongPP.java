package com.lapissea.dfs.utils.iterableplus;

import com.lapissea.dfs.Utils;

import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.StringJoiner;
import java.util.function.LongConsumer;
import java.util.function.LongFunction;
import java.util.function.LongPredicate;
import java.util.function.LongUnaryOperator;

public interface IterableLongPP{
	
	final class ArrayIter implements LongIterator{
		private final long[] data;
		private       int    i;
		public ArrayIter(long[] data){ this.data = data; }
		@Override
		public boolean hasNext(){ return i<data.length; }
		@Override
		public long nextLong(){ return data[i++]; }
	}
	
	final class SingleIter implements LongIterator{
		private final long    value;
		private       boolean done;
		public SingleIter(long value){ this.value = value; }
		@Override
		public boolean hasNext(){ return !done; }
		@Override
		public long nextLong(){
			if(done) throw new NoSuchElementException();
			done = true;
			return value;
		}
	}
	
	static IterableLongPP empty(){
		return () -> new LongIterator(){
			@Override
			public boolean hasNext(){
				return false;
			}
			@Override
			public long nextLong(){
				throw new NoSuchElementException();
			}
		};
	}
	
	default long sum(){
		long sum  = 0;
		var  iter = iterator();
		while(iter.hasNext()){
			sum += iter.nextLong();
		}
		return sum;
	}
	
	default OptionalLong min(){
		var iter = iterator();
		if(!iter.hasNext()) return OptionalLong.empty();
		long res = Long.MAX_VALUE;
		while(iter.hasNext()){
			res = Math.min(res, iter.nextLong());
		}
		return OptionalLong.of(res);
	}
	default OptionalLong max(){
		var iter = iterator();
		if(!iter.hasNext()) return OptionalLong.empty();
		long res = Long.MIN_VALUE;
		while(iter.hasNext()){
			res = Math.max(res, iter.nextLong());
		}
		return OptionalLong.of(res);
	}
	
	record Bounds(long min, long max){ }
	default Optional<Bounds> bounds(){
		var iter = iterator();
		if(!iter.hasNext()) return Optional.empty();
		long max = Long.MIN_VALUE, min = Long.MAX_VALUE;
		while(iter.hasNext()){
			var val = iter.nextLong();
			max = Math.max(max, val);
			min = Math.min(min, val);
		}
		return Optional.of(new Bounds(min, max));
	}
	
	/**
	 * Use when the count is O(1) or known to be cheap
	 */
	default long[] collectToArrayCounting(){
		var res  = new long[count()];
		var iter = iterator();
		for(int i = 0; i<res.length; i++){
			res[i] = iter.nextLong();
		}
		return res;
	}
	default long[] collectToArray(){
		return collectToArray(8);
	}
	default long[] collectToArray(int initialSize){
		var iter = iterator();
		var res  = new long[initialSize];
		int i    = 0;
		while(iter.hasNext()){
			if(i == res.length) res = Utils.growArr(res);
			res[i++] = iter.nextLong();
		}
		if(res.length != i) return Arrays.copyOf(res, i);
		return res;
	}
	
	default String joinAsStrings()                      { return joinAsStrings(""); }
	default String joinAsStrings(CharSequence delimiter){ return joinAsStrings(delimiter, "", ""); }
	default String joinAsStrings(CharSequence delimiter, CharSequence prefix, CharSequence suffix){
		var res  = new StringJoiner(delimiter, prefix, suffix);
		var iter = this.iterator();
		while(iter.hasNext()){
			res.add(Long.toString(iter.nextLong()));
		}
		return res.toString();
	}
	
	default int count(){
		int count = 0;
		var iter  = iterator();
		while(iter.hasNext()){
			var ignore = iter.nextLong();
			count++;
		}
		return count;
	}
	
	LongIterator iterator();
	
	default IterableLongPP map(LongUnaryOperator map){
		return () -> {
			var src = IterableLongPP.this.iterator();
			return new LongIterator(){
				@Override
				public boolean hasNext(){
					return src.hasNext();
				}
				@Override
				public long nextLong(){
					return map.applyAsLong(src.nextLong());
				}
			};
		};
	}
	
	default <T> IterablePP<T> mapToObj(LongFunction<T> function){
		return () -> {
			var src = IterableLongPP.this.iterator();
			return new Iterator<>(){
				@Override
				public boolean hasNext(){
					return src.hasNext();
				}
				@Override
				public T next(){
					return function.apply(src.nextLong());
				}
			};
		};
	}
	
	default IterablePP<Long> box(){
		return () -> {
			var src = IterableLongPP.this.iterator();
			return new Iterator<>(){
				@Override
				public boolean hasNext(){
					return src.hasNext();
				}
				@Override
				public Long next(){
					return src.nextLong();
				}
			};
		};
	}
	
	
	default IterableLongPP skip(int count){
		if(count<0) throw new IllegalArgumentException("count cannot be negative");
		return () -> {
			var iter = IterableLongPP.this.iterator();
			for(int i = 0; i<count; i++){
				if(!iter.hasNext()) break;
				iter.nextLong();
			}
			return iter;
		};
	}
	
	default IterableLongPP limit(int maxLen){
		if(maxLen<0) throw new IllegalArgumentException("maxLen cannot be negative");
		return () -> {
			var src = IterableLongPP.this.iterator();
			return new LongIterator(){
				private int count;
				
				@Override
				public boolean hasNext(){
					return maxLen>count && src.hasNext();
				}
				@Override
				public long nextLong(){
					count++;
					return src.nextLong();
				}
			};
		};
	}
	
	default boolean noneMatch(LongPredicate predicate){
		return !anyMatch(predicate);
	}
	default boolean anyMatch(LongPredicate predicate){
		var iter = iterator();
		while(iter.hasNext()){
			var t = iter.nextLong();
			if(predicate.test(t)){
				return true;
			}
		}
		return false;
	}
	default boolean allMatch(LongPredicate predicate){
		var iter = iterator();
		while(iter.hasNext()){
			var t = iter.nextLong();
			if(!predicate.test(t)){
				return false;
			}
		}
		return true;
	}
	
	default OptionalDouble average(){
		long sum = 0, count = 0;
		
		var iter = iterator();
		while(iter.hasNext()){
			sum += iter.nextLong();
			count++;
		}
		
		if(count == 0) return OptionalDouble.empty();
		return OptionalDouble.of((double)sum/count);
	}
	
	default void forEach(LongConsumer consumer){
		var iter = iterator();
		while(iter.hasNext()){
			var element = iter.nextLong();
			consumer.accept(element);
		}
	}
	default void forEach(LongPredicate predicate){
		var iter = iterator();
		while(iter.hasNext()){
			var element = iter.nextLong();
			if(!predicate.test(element)){
				break;
			}
		}
	}
	
	default IterableLongPP distinct(){
		return () -> {
			return new LongIterator(){
				private long[] sorted;
				private int    i;
				
				private long[] sort(){
					return sorted = IterableLongPP.this.box().sorted().mapToLong().collectToArray();
				}
				
				@Override
				public boolean hasNext(){
					var s = sorted;
					if(s == null) s = sort();
					return i<s.length;
				}
				@Override
				public long nextLong(){
					var s = sorted;
					if(s == null) s = sort();
					if(i>=s.length) throw new NoSuchElementException();
					return s[i++];
				}
			};
		};
	}
	
}
