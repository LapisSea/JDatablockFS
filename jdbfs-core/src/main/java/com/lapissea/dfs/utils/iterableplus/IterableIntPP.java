package com.lapissea.dfs.utils.iterableplus;


import com.lapissea.dfs.Utils;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.StringJoiner;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import java.util.function.IntToLongFunction;
import java.util.function.IntUnaryOperator;

@SuppressWarnings("unused")
public interface IterableIntPP{
	
	final class ArrayIter implements IntIterator{
		private final int[] data;
		private       int   i;
		public ArrayIter(int[] data){ this.data = data; }
		@Override
		public boolean hasNext(){ return i<data.length; }
		@Override
		public int nextInt(){ return data[i++]; }
	}
	
	final class SingleIter implements IntIterator{
		private final int     value;
		private       boolean done;
		public SingleIter(int value){ this.value = value; }
		@Override
		public boolean hasNext(){ return !done; }
		@Override
		public int nextInt(){
			if(done) throw new NoSuchElementException();
			done = true;
			return value;
		}
	}
	
	static IterableIntPP empty(){
		return new Iters.DefaultIntIterable(){
			@Override
			public IntIterator iterator(){
				return new IntIterator(){
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
		};
	}
	
	default int getFirst(){
		var iter = iterator();
		if(iter.hasNext()) return iter.nextInt();
		throw new NoSuchElementException();
	}
	
	default OptionalInt findFirst(){
		var iter = iterator();
		if(!iter.hasNext()) return OptionalInt.empty();
		return OptionalInt.of(iter.nextInt());
	}
	default OptionalInt firstMatching(IntPredicate predicate){
		var iter = iterator();
		while(iter.hasNext()){
			var element = iter.nextInt();
			if(predicate.test(element)){
				return OptionalInt.of(element);
			}
		}
		return OptionalInt.empty();
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
	default int[] toArrayCounting(){
		var res  = new int[count()];
		var iter = iterator();
		for(int i = 0; i<res.length; i++){
			res[i] = iter.nextInt();
		}
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
	
	default IterableLongPP addOverflowFiltered(long val){
		return mapToLong().addOverflowFiltered(val);
	}
	default IterableIntPP addOverflowFiltered(int val){
		return filter(other -> {
			var added = other + (long)val;
			return added>=Integer.MIN_VALUE && added<=Integer.MAX_VALUE;
		}).map(other -> other + val);
	}
	default IterableLongPP addExact(long val){
		return mapToLong(other -> Math.addExact(other, val));
	}
	default IterableLongPP add(long val){
		return mapToLong(other -> other + val);
	}
	
	default IterableIntPP addExact(int val){
		return map(other -> Math.addExact(other, val));
	}
	default IterableIntPP add(int val){
		return map(other -> other + val);
	}
	
	default IterableIntPP map(IntUnaryOperator map){
		return new Iters.DefaultIntIterable(){
			@Override
			public IntIterator iterator(){
				var src = IterableIntPP.this.iterator();
				return new IntIterator(){
					@Override
					public boolean hasNext(){
						return src.hasNext();
					}
					@Override
					public int nextInt(){
						return map.applyAsInt(src.nextInt());
					}
				};
			}
		};
	}
	default IterableIntPP mapExact(IntToLongFunction map){
		return new Iters.DefaultIntIterable(){
			@Override
			public IntIterator iterator(){
				var src = IterableIntPP.this.iterator();
				return new IntIterator(){
					@Override
					public boolean hasNext(){
						return src.hasNext();
					}
					@Override
					public int nextInt(){
						return Math.toIntExact(map.applyAsLong(src.nextInt()));
					}
				};
			}
		};
	}
	
	default IterablePP<Integer> box(){
		return new Iters.DefaultIterable<Integer>(){
			@Override
			public Iterator<Integer> iterator(){
				var src = IterableIntPP.this.iterator();
				return new Iterator<>(){
					@Override
					public boolean hasNext(){
						return src.hasNext();
					}
					@Override
					public Integer next(){
						return src.nextInt();
					}
				};
			}
		};
	}
	
	default IterableIntPP filter(IntPredicate filter){
		return new Iters.DefaultIntIterable(){
			@Override
			public IntIterator iterator(){
				var src = IterableIntPP.this.iterator();
				return new Iters.FindingIntIterator(){
					@Override
					protected boolean doNext(){
						while(true){
							if(!src.hasNext()) return false;
							var t = src.nextInt();
							if(filter.test(t)){
								reportFound(t);
								return true;
							}
						}
					}
				};
			}
		};
	}
	
	default <T> IterablePP<T> mapToObj(IntFunction<T> function){
		return new Iters.DefaultIterable<>(){
			@Override
			public Iterator<T> iterator(){
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
			}
		};
	}
	
	default IterableLongPP mapToLong(){ return mapToLong(e -> e); }
	default IterableLongPP mapToLong(IntToLongFunction mapper){
		return new Iters.DefaultLongIterable(){
			@Override
			public LongIterator iterator(){
				var iter = IterableIntPP.this.iterator();
				return new LongIterator(){
					@Override
					public boolean hasNext(){
						return iter.hasNext();
					}
					@Override
					public long nextLong(){
						return mapper.applyAsLong(iter.nextInt());
					}
				};
			}
		};
	}
	
	
	default IterableIntPP skip(int count){
		if(count<0) throw new IllegalArgumentException("count cannot be negative");
		return new Iters.DefaultIntIterable(){
			@Override
			public IntIterator iterator(){
				var iter = IterableIntPP.this.iterator();
				for(int i = 0; i<count; i++){
					if(!iter.hasNext()) break;
					iter.nextInt();
				}
				return iter;
			}
		};
	}
	
	default IterableIntPP limit(int maxLen){
		if(maxLen<0) throw new IllegalArgumentException("maxLen cannot be negative");
		return new Iters.DefaultIntIterable(){
			@Override
			public IntIterator iterator(){
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
			}
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
	
	default void forEach(IntConsumer consumer){
		var iter = iterator();
		while(iter.hasNext()){
			var element = iter.nextInt();
			consumer.accept(element);
		}
	}
	default void forEach(IntPredicate predicate){
		var iter = iterator();
		while(iter.hasNext()){
			var element = iter.nextInt();
			if(!predicate.test(element)){
				break;
			}
		}
	}
	
	default IterableIntPP sorted(){
		return new Iters.DefaultIntIterable(){
			@Override
			public IntIterator iterator(){
				var src = IterableIntPP.this;
				return new IntIterator(){
					private int[] sorted;
					private int   i;
					private int[] sort(){
						var arr = src.toArray();
						Arrays.sort(arr);
						return sorted = arr;
					}
					@Override
					public boolean hasNext(){
						var s = sorted;
						if(s == null) s = sort();
						return i<s.length;
					}
					@Override
					public int nextInt(){
						var s = sorted;
						if(s == null) s = sort();
						if(i == s.length) throw new NoSuchElementException();
						return s[i++];
					}
				};
			}
		};
	}
	
	default <T> IterableIntPP sorted(IntFunction<T> map, Comparator<T> compare){
		return new Iters.DefaultIntIterable(){
			@Override
			public IntIterator iterator(){
				var src = IterableIntPP.this;
				return new IntIterator(){
					private int[] sorted;
					private int   i;
					private int[] sort(){
						//TODO: implement int sort without boxing
						return sorted = src.mapToObj(i1 -> i1).sorted((a, b) -> compare.compare(map.apply(a), map.apply(b))).mapToInt().toArray();
					}
					@Override
					public boolean hasNext(){
						var s = sorted;
						if(s == null) s = sort();
						return i<s.length;
					}
					@Override
					public int nextInt(){
						var s = sorted;
						if(s == null) s = sort();
						if(i == s.length) throw new NoSuchElementException();
						return s[i++];
					}
				};
			}
		};
	}
	
	
	record Idx(int index, int val) implements Map.Entry<Integer, Integer>{
		@Override
		public Integer getKey(){ return index; }
		@Override
		public Integer getValue(){ return val; }
		@Override
		public Integer setValue(Integer value){ throw new UnsupportedOperationException(); }
	}
	
	record Ldx(long index, int val) implements Map.Entry<Long, Integer>{
		@Override
		public Long getKey(){ return index; }
		@Override
		public Integer getValue(){ return val; }
		@Override
		public Integer setValue(Integer value){ throw new UnsupportedOperationException(); }
	}
	
	default IterablePP<IterableIntPP.Idx> enumerate(){
		return new Iters.DefaultIterable<>(){
			@Override
			public Iterator<IterableIntPP.Idx> iterator(){
				var src = IterableIntPP.this.iterator();
				return new Iterator<>(){
					private int index;
					@Override
					public boolean hasNext(){
						return src.hasNext();
					}
					@Override
					public IterableIntPP.Idx next(){
						preIncrementInt(index);
						return new IterableIntPP.Idx(index++, src.nextInt());
					}
				};
			}
		};
	}
	default IterablePP<IterableIntPP.Ldx> enumerateL(){
		return new Iters.DefaultIterable<>(){
			@Override
			public Iterator<IterableIntPP.Ldx> iterator(){
				var src = IterableIntPP.this.iterator();
				return new Iterator<>(){
					private long index;
					@Override
					public boolean hasNext(){
						return src.hasNext();
					}
					@Override
					public IterableIntPP.Ldx next(){
						preIncrementLong(index);
						return new IterableIntPP.Ldx(index++, src.nextInt());
					}
				};
			}
		};
	}
	private static void preIncrementLong(long index){
		if(index == Long.MAX_VALUE) throw new IllegalStateException("Too many elements");
	}
	private static void preIncrementInt(int num){
		if(num == Integer.MAX_VALUE) throw new IllegalStateException("Too many elements");
	}
}
