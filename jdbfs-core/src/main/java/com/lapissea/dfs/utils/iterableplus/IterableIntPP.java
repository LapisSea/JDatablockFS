package com.lapissea.dfs.utils.iterableplus;


import com.lapissea.dfs.Utils;

import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.StringJoiner;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;

public interface IterableIntPP{
	
	final class ArrayIterI implements IntIterator{
		private final int[] data;
		private       int   i;
		public ArrayIterI(int[] data){ this.data = data; }
		@Override
		public boolean hasNext(){ return i<data.length; }
		@Override
		public int nextInt(){ return data[i++]; }
	}
	
	static IterableIntPP empty(){
		return () -> new IntIterator(){
			@Override
			public boolean hasNext(){
				return false;
			}
			@Override
			public int nextInt(){
				throw new NoSuchElementException();
			}
		};
	}
	
	default int sum(){
		int sum  = 0;
		var iter = iterator();
		while(iter.hasNext()){
			sum += iter.nextInt();
		}
		return sum;
	}
	
	default OptionalInt min(){
		var iter = iterator();
		if(!iter.hasNext()) return OptionalInt.empty();
		int res = Integer.MAX_VALUE;
		while(iter.hasNext()){
			res = Math.min(res, iter.nextInt());
		}
		return OptionalInt.of(res);
	}
	default OptionalInt max(){
		var iter = iterator();
		if(!iter.hasNext()) return OptionalInt.empty();
		int res = Integer.MIN_VALUE;
		while(iter.hasNext()){
			res = Math.max(res, iter.nextInt());
		}
		return OptionalInt.of(res);
	}
	
	record Bounds(int min, int max){ }
	default Optional<Bounds> bounds(){
		var iter = iterator();
		if(!iter.hasNext()) return Optional.empty();
		int max = Integer.MIN_VALUE, min = Integer.MAX_VALUE;
		while(iter.hasNext()){
			var val = iter.nextInt();
			max = Math.max(max, val);
			min = Math.min(min, val);
		}
		return Optional.of(new Bounds(min, max));
	}
	
	/**
	 * Use when the count is O(1) or known to be cheap
	 */
	default int[] collectToArrayCounting(){
		var res  = new int[count()];
		var iter = iterator();
		for(int i = 0; i<res.length; i++){
			res[i] = iter.nextInt();
		}
		return res;
	}
	default int[] collectToArray(){
		return collectToArray(8);
	}
	default int[] collectToArray(int initialSize){
		var iter = iterator();
		var res  = new int[initialSize];
		int i    = 0;
		while(iter.hasNext()){
			if(i == res.length) res = Utils.growArr(res);
			res[i++] = iter.nextInt();
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
			res.add(Integer.toString(iter.nextInt()));
		}
		return res.toString();
	}
	
	default int count(){
		int count = 0;
		var iter  = iterator();
		while(iter.hasNext()){
			var ignore = iter.nextInt();
			count++;
		}
		return count;
	}
	
	IntIterator iterator();
	
	default <T> IterablePP<T> mapToObj(IntFunction<T> function){
		return () -> {
			var src = IterableIntPP.this.iterator();
			return new Iterator<>(){
				@Override
				public boolean hasNext(){
					return src.hasNext();
				}
				@Override
				public T next(){
					return function.apply(src.nextInt());
				}
			};
		};
	}
	
	
	default IterableIntPP skip(int count){
		if(count<0) throw new IllegalArgumentException("count cannot be negative");
		return () -> {
			var iter = IterableIntPP.this.iterator();
			for(int i = 0; i<count; i++){
				if(!iter.hasNext()) break;
				iter.nextInt();
			}
			return iter;
		};
	}
	
	default IterableIntPP limit(int maxLen){
		if(maxLen<0) throw new IllegalArgumentException("maxLen cannot be negative");
		return () -> {
			var src = IterableIntPP.this.iterator();
			return new IntIterator(){
				private int count;
				
				@Override
				public boolean hasNext(){
					return maxLen>count && src.hasNext();
				}
				@Override
				public int nextInt(){
					count++;
					return src.nextInt();
				}
			};
		};
	}
	
	default boolean noneMatch(IntPredicate predicate){
		return !anyMatch(predicate);
	}
	default boolean anyMatch(IntPredicate predicate){
		var iter = iterator();
		while(iter.hasNext()){
			var t = iter.nextInt();
			if(predicate.test(t)){
				return true;
			}
		}
		return false;
	}
	default boolean allMatch(IntPredicate predicate){
		var iter = iterator();
		while(iter.hasNext()){
			var t = iter.nextInt();
			if(!predicate.test(t)){
				return false;
			}
		}
		return true;
	}
	default int[] toArray(){
		int[] res  = new int[8];
		int   size = 0;
		var   iter = iterator();
		while(iter.hasNext()){
			if(size == res.length) res = Utils.growArr(res);
			res[size++] = iter.nextInt();
		}
		return Arrays.copyOf(res, size);
	}
}
