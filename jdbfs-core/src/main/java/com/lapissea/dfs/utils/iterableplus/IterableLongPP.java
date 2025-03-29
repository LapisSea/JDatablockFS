package com.lapissea.dfs.utils.iterableplus;

import com.lapissea.dfs.Utils;
import org.roaringbitmap.longlong.Roaring64Bitmap;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.StringJoiner;
import java.util.function.LongConsumer;
import java.util.function.LongFunction;
import java.util.function.LongPredicate;
import java.util.function.LongToIntFunction;
import java.util.function.LongUnaryOperator;

@SuppressWarnings("unused")
public interface IterableLongPP{
	
	interface SizedPP extends IterableLongPP{
		static OptionalInt tryGet(IterableLongPP iter){
			if(iter instanceof SizedPP s){
				return s.getSize();
			}
			return OptionalInt.empty();
		}
		
		abstract class Default extends Iters.DefaultLongIterable implements SizedPP{ }
		
		OptionalInt getSize();
		
		@Override
		default int count(){
			var s = getSize();
			if(s.isPresent()) return s.getAsInt();
			return IterableLongPP.super.count();
		}
	}
	
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
		return new SizedPP.Default(){
			@Override
			public OptionalInt getSize(){
				return OptionalInt.of(0);
			}
			@Override
			public LongIterator iterator(){
				return new LongIterator(){
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
		};
	}
	
	default long getFirst(){
		var iter = iterator();
		if(iter.hasNext()) return iter.nextLong();
		throw new NoSuchElementException();
	}
	
	default OptionalLong findFirst(){
		var iter = iterator();
		if(!iter.hasNext()) return OptionalLong.empty();
		return OptionalLong.of(iter.nextLong());
	}
	default OptionalLong firstMatching(LongPredicate predicate){
		var iter = iterator();
		while(iter.hasNext()){
			var element = iter.nextLong();
			if(predicate.test(element)){
				return OptionalLong.of(element);
			}
		}
		return OptionalLong.empty();
	}
	
	default long sum(){
		long sum  = 0;
		var  iter = iterator();
		while(iter.hasNext()){
			sum += iter.nextLong();
		}
		return sum;
	}
	
	default long min(int defaultValue){ return min().orElse(defaultValue); }
	default OptionalLong min(){
		var iter = iterator();
		if(!iter.hasNext()) return OptionalLong.empty();
		long res = Long.MAX_VALUE;
		while(iter.hasNext()){
			res = Math.min(res, iter.nextLong());
		}
		return OptionalLong.of(res);
	}
	default long max(int defaultValue){ return max().orElse(defaultValue); }
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
	default long[] toArrayCounting(){
		var res  = new long[count()];
		var iter = iterator();
		for(int i = 0; i<res.length; i++){
			res[i] = iter.nextLong();
		}
		return res;
	}
	default long[] toArray(){
		var size = SizedPP.tryGet(this).orElse(8);
		return toArray(size);
	}
	default long[] toArray(int initialSize){
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
	
	
	default IterableLongPP flatMap(LongFunction<IterableLongPP> flatten){
		return new Iters.DefaultLongIterable(){
			@Override
			public LongIterator iterator(){
				var src = IterableLongPP.this.iterator();
				return new Iters.FindingLongIterator(){
					private LongIterator flat;
					@Override
					protected boolean doNext(){
						while(true){
							if(flat == null || !flat.hasNext()){
								if(!src.hasNext()) return false;
								flat = flatten.apply(src.nextLong()).iterator();
								continue;
							}
							reportFound(flat.nextLong());
							return true;
						}
					}
				};
			}
		};
	}
	
	default IterableLongPP addOverflowFiltered(long val){
		return filter(other -> {
			long r = other + val;
			return ((other^r)&(val^r))>=0;
		}).map(other -> other + val);
	}
	default IterableLongPP addExact(long val){
		return map(other -> Math.addExact(other, val));
	}
	default IterableLongPP add(long val){
		return map(other -> other + val);
	}
	
	default IterableLongPP map(LongUnaryOperator map){
		return new SizedPP.Default(){
			@Override
			public OptionalInt getSize(){
				return SizedPP.tryGet(IterableLongPP.this);
			}
			@Override
			public LongIterator iterator(){
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
			}
		};
	}
	
	default IterableIntPP mapToIntOverflowFiltered(){ return filterIntRange().mapToInt(); }
	default IterableIntPP mapToIntExact()           { return mapToInt(Math::toIntExact); }
	default IterableIntPP mapToInt()                { return mapToInt(e -> (int)e); }
	default IterableIntPP mapToInt(LongToIntFunction mapper){
		return new IterableIntPP.SizedPP.Default(){
			@Override
			public OptionalInt getSize(){
				return IterableLongPP.SizedPP.tryGet(IterableLongPP.this);
			}
			@Override
			public IntIterator iterator(){
				var iter = IterableLongPP.this.iterator();
				return new IntIterator(){
					@Override
					public boolean hasNext(){
						return iter.hasNext();
					}
					@Override
					public int nextInt(){
						return mapper.applyAsInt(iter.nextLong());
					}
				};
			}
		};
	}
	
	default <T> IterablePP<T> mapToObj(LongFunction<T> function){
		return new Iters.DefaultIterable<>(){
			@Override
			public Iterator<T> iterator(){
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
			}
		};
	}
	
	default IterableLongPP filterIntRange(){
		return filter(v -> v>=Integer.MIN_VALUE && v<=Integer.MAX_VALUE);
	}
	
	default IterableLongPP retaining(long... toKeep){
		return filter(i -> {
			for(var j : toKeep){
				if(i == j) return true;
			}
			return false;
		});
	}
	default IterableLongPP removing(long... toRemove){
		return filter(i -> {
			for(var j : toRemove){
				if(i == j) return false;
			}
			return true;
		});
	}
	
	default IterableLongPP filter(LongPredicate filter){
		return new Iters.DefaultLongIterable(){
			@Override
			public LongIterator iterator(){
				var src = IterableLongPP.this.iterator();
				return new Iters.FindingLongIterator(){
					@Override
					protected boolean doNext(){
						while(true){
							if(!src.hasNext()) return false;
							var t = src.nextLong();
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
	
	default IterablePP<Long> box(){
		return new Iters.DefaultIterable<>(){
			@Override
			public Iterator<Long> iterator(){
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
			}
		};
	}
	
	
	default IterableLongPP skip(int count){
		if(count<0) throw new IllegalArgumentException("count cannot be negative");
		return new SizedPP.Default(){
			@Override
			public OptionalInt getSize(){
				var s = SizedPP.tryGet(IterableLongPP.this);
				if(s.isEmpty()) return OptionalInt.empty();
				return OptionalInt.of(Math.max(0, s.getAsInt() - count));
			}
			@Override
			public LongIterator iterator(){
				var iter = IterableLongPP.this.iterator();
				for(int i = 0; i<count; i++){
					if(!iter.hasNext()) break;
					iter.nextLong();
				}
				return iter;
			}
		};
	}
	
	default IterableLongPP limit(int maxLen){
		if(maxLen<0) throw new IllegalArgumentException("maxLen cannot be negative");
		return new SizedPP.Default(){
			@Override
			public OptionalInt getSize(){
				var s = SizedPP.tryGet(IterableLongPP.this);
				if(s.isEmpty()) return OptionalInt.empty();
				return OptionalInt.of(Math.min(s.getAsInt(), maxLen));
			}
			@Override
			public LongIterator iterator(){
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
			}
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
	
	record Idx(int index, long val) implements Map.Entry<Integer, Long>{
		@Override
		public Integer getKey(){ return index; }
		@Override
		public Long getValue(){ return val; }
		@Override
		public Long setValue(Long value){ throw new UnsupportedOperationException(); }
	}
	
	record Ldx(long index, long val) implements Map.Entry<Long, Long>{
		@Override
		public Long getKey(){ return index; }
		@Override
		public Long getValue(){ return val; }
		@Override
		public Long setValue(Long value){ throw new UnsupportedOperationException(); }
	}
	
	default IterablePP<Idx> enumerate(){
		return new Iters.DefaultIterable<>(){
			@Override
			public Iterator<IterableLongPP.Idx> iterator(){
				var src = IterableLongPP.this.iterator();
				return new Iterator<>(){
					private int index;
					@Override
					public boolean hasNext(){
						return src.hasNext();
					}
					@Override
					public IterableLongPP.Idx next(){
						preIncrementInt(index);
						return new IterableLongPP.Idx(index++, src.nextLong());
					}
				};
			}
		};
	}
	default IterablePP<Ldx> enumerateL(){
		return new Iters.DefaultIterable<>(){
			@Override
			public Iterator<IterableLongPP.Ldx> iterator(){
				var src = IterableLongPP.this.iterator();
				return new Iterator<>(){
					private long index;
					@Override
					public boolean hasNext(){
						return src.hasNext();
					}
					@Override
					public IterableLongPP.Ldx next(){
						preIncrementLong(index);
						return new IterableLongPP.Ldx(index++, src.nextLong());
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
	
	
	default IterableLongPP distinct(){
		return new Iters.DefaultLongIterable(){
			@Override
			public LongIterator iterator(){
				var src = IterableLongPP.this.iterator();
				return new Iters.FindingLongIterator(){
					private final Roaring64Bitmap seen = new Roaring64Bitmap();
					@Override
					protected boolean doNext(){
						while(true){
							if(!src.hasNext()) return false;
							var t = src.nextLong();
							if(!seen.contains(t)){
								seen.addLong(t);
								reportFound(t);
								return true;
							}
						}
					}
				};
			}
		};
	}
}
