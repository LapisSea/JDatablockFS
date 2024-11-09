package com.lapissea.dfs.utils.iterableplus;


import com.lapissea.dfs.Utils;
import com.lapissea.dfs.utils.IntHashSet;
import com.lapissea.util.ZeroArrays;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import java.util.function.IntToLongFunction;
import java.util.function.IntUnaryOperator;

@SuppressWarnings("unused")
public interface IterableIntPP{
	
	interface SizedPP extends IterableIntPP{
		static OptionalInt tryGet(IterableIntPP iter){
			if(iter instanceof SizedPP s){
				return s.getSize();
			}
			return OptionalInt.empty();
		}
		
		abstract class Default<T> extends Iters.DefaultIntIterable implements SizedPP{ }
		
		OptionalInt getSize();
		
		@Override
		default int count(){
			var s = getSize();
			if(s.isPresent()) return s.getAsInt();
			return IterableIntPP.super.count();
		}
	}
	
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
		return new SizedPP.Default<>(){
			@Override
			public OptionalInt getSize(){ return OptionalInt.of(0); }
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
	
	default boolean anyIs(int needle){
		var iter = iterator();
		while(iter.hasNext()){
			if(iter.nextInt() == needle){
				return true;
			}
		}
		return false;
	}
	
	default int sum(){
		int sum  = 0;
		var iter = iterator();
		while(iter.hasNext()){
			sum += iter.nextInt();
		}
		return sum;
	}
	
	default int min(int defaultValue){ return min().orElse(defaultValue); }
	default OptionalInt min(){
		var iter = iterator();
		if(!iter.hasNext()) return OptionalInt.empty();
		int res = Integer.MAX_VALUE;
		while(iter.hasNext()){
			res = Math.min(res, iter.nextInt());
		}
		return OptionalInt.of(res);
	}
	default int max(int defaultValue){ return max().orElse(defaultValue); }
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
		do{
			var val = iter.nextInt();
			max = Math.max(max, val);
			min = Math.min(min, val);
		}while(iter.hasNext());
		return Optional.of(new Bounds(min, max));
	}
	
	default String joinAsStr()                { return joinAsStr(""); }
	default String joinAsStr(String delimiter){ return joinAsStr(delimiter, "", ""); }
	default String joinAsStr(String delimiter, String prefix, String suffix){
		return mapToObj(Integer::toString).joinAsStr(delimiter, prefix, suffix);
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
	default boolean hasDuplicates(){
		OptionalInt size = SizedPP.tryGet(this);
		IntHashSet  res;
		if(size.isPresent()){
			var siz = size.getAsInt();
			if(siz<=1) return false;
			res = new IntHashSet((int)Math.ceil(siz/0.75), 0.75);
		}else res = new IntHashSet();
		
		var iter = this.iterator();
		while(iter.hasNext()){
			var e = iter.nextInt();
			if(!res.add(e)){
				return true;
			}
		}
		return false;
	}
	
	IntIterator iterator();
	
	default IterableLongPP addOverflowFiltered(long addend){
		return mapToLong().addOverflowFiltered(addend);
	}
	default IterableIntPP addOverflowFiltered(int addend){
		return filter(other -> {
			var added = other + (long)addend;
			return added>=Integer.MIN_VALUE && added<=Integer.MAX_VALUE;
		}).map(other -> other + addend);
	}
	default IterableLongPP addExact(long addend){
		return mapToLong(other -> Math.addExact(other, addend));
	}
	default IterableLongPP add(long addend){
		return mapToLong(other -> other + addend);
	}
	
	default IterableIntPP addExact(int addend){
		return map(other -> Math.addExact(other, addend));
	}
	default IterableIntPP add(int addend){
		return map(other -> other + addend);
	}
	
	default IterableIntPP mulExact(int multiplier){
		return map(other -> Math.multiplyExact(other, multiplier));
	}
	default IterableIntPP mul(int multiplier){
		return map(other -> other*multiplier);
	}
	default IterableLongPP mulExact(long multiplier){
		return mapToLong(other -> Math.multiplyExact(other, multiplier));
	}
	default IterableLongPP mul(long multiplier){
		return mapToLong(other -> other*multiplier);
	}
	
	default IterableIntPP divExact(int divisor){
		return map(other -> Math.divideExact(other, divisor));
	}
	default IterableIntPP div(int divisor){
		return map(other -> other/divisor);
	}
	
	default IterableIntPP.SizedPP map(IntUnaryOperator map){
		return new SizedPP.Default<>(){
			@Override
			public OptionalInt getSize(){ return SizedPP.tryGet(IterableIntPP.this); }
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
		return new SizedPP.Default<>(){
			@Override
			public OptionalInt getSize(){ return SizedPP.tryGet(IterableIntPP.this); }
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
		return new IterablePP.SizedPP.Default<>(){
			@Override
			public OptionalInt getSize(){ return IterableIntPP.SizedPP.tryGet(IterableIntPP.this); }
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
	
	default IterableIntPP retaining(BitSet toKeep){
		return filter(toKeep::get);
	}
	default IterableIntPP retaining(int... toKeep){
		return filter(i -> {
			for(int j : toKeep){
				if(i == j) return true;
			}
			return false;
		});
	}
	default IterableIntPP removing(BitSet toRemove){
		return filter(i -> !toRemove.get(i));
	}
	default IterableIntPP removing(int... toRemove){
		return filter(i -> {
			for(int j : toRemove){
				if(i == j) return false;
			}
			return true;
		});
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
		return new IterablePP.SizedPP.Default<>(){
			@Override
			public OptionalInt getSize(){ return IterableIntPP.SizedPP.tryGet(IterableIntPP.this); }
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
	
	default IterableIntPP.SizedPP flatMap(IntFunction<IterableIntPP> flatten){
		return new IterableIntPP.SizedPP.Default<>(){
			@Override
			public OptionalInt getSize(){
				int size = 0;
				var src  = IterableIntPP.this.iterator();
				while(src.hasNext()){
					var t = src.nextInt();
					var sizO = switch(flatten.apply(t)){
						case Collection<?> c -> OptionalInt.of(c.size());
						case SizedPP s -> s.getSize();
						default -> OptionalInt.empty();
					};
					if(sizO.isEmpty()) return OptionalInt.empty();
					var siz = sizO.getAsInt();
					
					var lSize = size + (long)siz;
					if(lSize>Integer.MAX_VALUE) return OptionalInt.empty();
					size = (int)lSize;
				}
				return OptionalInt.of(size);
			}
			@Override
			public IntIterator iterator(){
				var src = IterableIntPP.this.iterator();
				return new Iters.FindingIntIterator(){
					private IntIterator flat;
					@Override
					protected boolean doNext(){
						while(true){
							if(flat == null || !flat.hasNext()){
								if(!src.hasNext()) return false;
								flat = flatten.apply(src.nextInt()).iterator();
								continue;
							}
							reportFound(flat.nextInt());
							return true;
						}
					}
				};
			}
		};
	}
	
	default IterableIntPP.SizedPP flatMapArray(IntFunction<int[]> flatten){
		return new IterableIntPP.SizedPP.Default<>(){
			@Override
			public OptionalInt getSize(){
				int size = 0;
				var src  = IterableIntPP.this.iterator();
				while(src.hasNext()){
					var t     = src.nextInt();
					var siz   = flatten.apply(t).length;
					var lSize = size + (long)siz;
					if(lSize>Integer.MAX_VALUE) return OptionalInt.empty();
					size = (int)lSize;
				}
				return OptionalInt.of(size);
			}
			@Override
			public IntIterator iterator(){
				var src = IterableIntPP.this.iterator();
				return new Iters.FindingIntIterator(){
					private int[] data;
					private int   index;
					@Override
					protected boolean doNext(){
						while(true){
							if(data == null || data.length == index){
								if(!src.hasNext()) return false;
								data = Objects.requireNonNull(flatten.apply(src.nextInt()));
								continue;
							}
							reportFound(data[index++]);
							return true;
						}
					}
				};
			}
		};
	}
	
	default IterableIntPP skip(int count){
		if(count<0) throw new IllegalArgumentException("count cannot be negative");
		return new SizedPP.Default<>(){
			@Override
			public OptionalInt getSize(){
				var siz = SizedPP.tryGet(IterableIntPP.this);
				if(siz.isPresent()) return OptionalInt.of(Math.max(0, siz.getAsInt() - count));
				return OptionalInt.empty();
			}
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
		return new SizedPP.Default<>(){
			@Override
			public OptionalInt getSize(){
				var siz = SizedPP.tryGet(IterableIntPP.this);
				if(siz.isPresent()) return OptionalInt.of(Math.min(maxLen, siz.getAsInt()));
				return OptionalInt.empty();
			}
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
	
	default char[] toCharArray(){
		var siz = SizedPP.tryGet(IterableIntPP.this);
		if(siz.isPresent() && siz.getAsInt() == 0){
			return ZeroArrays.ZERO_CHAR;
		}
		
		var iter = iterator();
		if(!iter.hasNext()){
			return ZeroArrays.ZERO_CHAR;
		}
		
		char[] res  = new char[siz.orElse(8)];
		int    size = 0;
		
		do{
			if(size == res.length) res = Utils.growArr(res);
			var i = iter.nextInt();
			var c = (char)i;
			if(c != i){
				throw new IllegalStateException("\"" + i + "\" is not a valid character value");
			}
			res[size++] = c;
		}while(iter.hasNext());
		
		if(res.length == size) return res;
		return Arrays.copyOf(res, size);
	}
	default int[] toArray(){
		var siz = SizedPP.tryGet(IterableIntPP.this);
		if(siz.isPresent() && siz.getAsInt() == 0){
			return ZeroArrays.ZERO_INT;
		}
		
		var iter = iterator();
		if(!iter.hasNext()){
			return ZeroArrays.ZERO_INT;
		}
		
		int[] res  = new int[siz.orElse(8)];
		int   size = 0;
		
		do{
			if(size == res.length) res = Utils.growArr(res);
			res[size++] = iter.nextInt();
		}while(iter.hasNext());
		
		if(res.length == size) return res;
		return Arrays.copyOf(res, size);
	}
	default IntHashSet toModSet(){
		var siz  = SizedPP.tryGet(IterableIntPP.this);
		var set  = new IntHashSet((int)(siz.orElse(8)/0.75f), 0.75f);
		var iter = iterator();
		while(iter.hasNext()){
			set.add(iter.nextInt());
		}
		return set;
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
		return new SizedPP.Default<>(){
			@Override
			public OptionalInt getSize(){ return SizedPP.tryGet(IterableIntPP.this); }
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
			@Override
			public int[] toArray(){
				var src = IterableIntPP.this;
				var arr = src.toArray();
				Arrays.sort(arr);
				return arr;
			}
		};
	}
	
	default <T> IterableIntPP sorted(IntFunction<T> map, Comparator<T> compare){
		return new SizedPP.Default<>(){
			@Override
			public OptionalInt getSize(){ return SizedPP.tryGet(IterableIntPP.this); }
			
			private int[] sortIter(IterableIntPP src){
				//TODO: implement int sort without boxing
				return src.box().sorted((a, b) -> {
					var av = map.apply(a);
					var bv = map.apply(b);
					return compare.compare(av, bv);
				}).mapToInt().toArray();
			}
			@Override
			public IntIterator iterator(){
				var src = IterableIntPP.this;
				return new IntIterator(){
					private int[] sorted;
					private int   i;
					private int[] sort(){
						return sorted = sortIter(src);
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
			@Override
			public int[] toArray(){
				var src = IterableIntPP.this;
				return sortIter(src);
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
		return new IterablePP.SizedPP.Default<>(){
			@Override
			public OptionalInt getSize(){ return IterableIntPP.SizedPP.tryGet(IterableIntPP.this); }
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
		return new IterablePP.SizedPP.Default<>(){
			@Override
			public OptionalInt getSize(){ return IterableIntPP.SizedPP.tryGet(IterableIntPP.this); }
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
	
	
	default IterableIntPP distinct(){
		return new Iters.DefaultIntIterable(){
			@Override
			public IntIterator iterator(){
				var src = IterableIntPP.this.iterator();
				return new Iters.FindingIntIterator(){
					private final IntHashSet seen = new IntHashSet();
					@Override
					protected boolean doNext(){
						while(true){
							if(!src.hasNext()) return false;
							var t = src.nextInt();
							if(seen.add(t)){
								reportFound(t);
								return true;
							}
						}
					}
				};
			}
			@Override
			public int count(){
				var seen = new IntHashSet();
				var src  = IterableIntPP.this.iterator();
				while(src.hasNext()){
					var v = src.nextInt();
					seen.add(v);
				}
				return seen.size();
			}
		};
	}
}
