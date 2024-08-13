package com.lapissea.dfs.utils.iterableplus;

import com.lapissea.dfs.Utils;
import com.lapissea.dfs.utils.OptionalPP;
import com.lapissea.dfs.utils.function.FunctionOI;
import com.lapissea.dfs.utils.function.FunctionOL;
import com.lapissea.util.function.UnsafePredicate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;
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
 *       simple and are more likely to be inlined by the JIT.
 *   </li>
 * </ul>
 */
@SuppressWarnings("unused")
public interface IterablePP<T> extends Iterable<T>{
	
	interface SizedPP<T> extends IterablePP<T>{
		static OptionalInt tryGet(IterablePP<?> iter){
			if(iter instanceof SizedPP<?> s){
				return s.getSize();
			}
			return OptionalInt.empty();
		}
		static OptionalInt tryGetUnknown(Iterable<?> iter){
			if(iter instanceof SizedPP<?> s){
				return s.getSize();
			}
			if(iter instanceof Collection<?> s){
				return OptionalInt.of(s.size());
			}
			return OptionalInt.empty();
		}
		
		abstract class Default<T> extends Iters.DefaultIterable<T> implements SizedPP<T>{ }
		
		OptionalInt getSize();
		
		@Override
		default boolean isEmpty(){
			var s = getSize();
			if(s.isPresent()) return s.getAsInt()>0;
			return IterablePP.super.isEmpty();
		}
		@Override
		default int count(){
			var s = getSize();
			if(s.isPresent()) return s.getAsInt();
			return IterablePP.super.count();
		}
		@Override
		default long countL(){
			var s = getSize();
			if(s.isPresent()) return s.getAsInt();
			return IterablePP.super.countL();
		}
	}
	
	default Stream<T> stream(){
		return StreamSupport.stream(spliterator(), false);
	}
	
	default boolean hasAny(){
		return !isEmpty();
	}
	default boolean isEmpty(){
		return !iterator().hasNext();
	}
	
	default T getFirst(){
		var iter = iterator();
		if(iter.hasNext()) return iter.next();
		throw new NoSuchElementException();
	}
	
	default Optional<T> findFirst(){
		for(T element : this){
			if(element == null) continue;
			return Optional.of(element);
		}
		return Optional.empty();
	}
	
	default Optional<T> findLast(){
		Iterator<T> iterator = this.iterator();
		if(!iterator.hasNext()) return Optional.empty();
		T last = null;
		do{
			var result = iterator.next();
			if(result != null) last = result;
		}while(iterator.hasNext());
		return Optional.ofNullable(last);
	}
	
	default Optional<T> firstNonNull(){
		for(var element : this){
			if(element == null) continue;
			return Optional.of(element);
		}
		return Optional.empty();
	}
	default <E extends Throwable> Optional<T> firstNotMatching(UnsafePredicate<T, E> predicate) throws E{
		for(var element : this){
			if(element == null) continue;
			if(!predicate.test(element)){
				return Optional.of(element);
			}
		}
		return Optional.empty();
	}
	default <E extends Throwable> Optional<T> firstMatching(UnsafePredicate<T, E> predicate) throws E{
		for(var element : this){
			if(element == null) continue;
			if(predicate.test(element)){
				return Optional.of(element);
			}
		}
		return Optional.empty();
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
		var s = tryGetSize();
		if(s.isPresent()) return s.getAsInt();
		
		long num = 0;
		for(var ignore : this){
			preIncrementLong(num);
			num++;
		}
		return num;
	}
	
	default OptionalPP<T> reduce(BinaryOperator<T> reducer){
		var src = iterator();
		if(!src.hasNext()) return OptionalPP.empty();
		
		var result = src.next();
		while(src.hasNext()){
			var next = src.next();
			result = reducer.apply(result, next);
		}
		return OptionalPP.of(result);
	}
	
	default T reduce(T initial, BinaryOperator<T> reducer){
		var result = initial;
		for(T next : this){
			result = reducer.apply(result, next);
		}
		return result;
	}
	
	default OptionalPP<T> minByI(ToIntFunction<T> sortProperty)   { return min(Comparator.comparingInt(sortProperty)); }
	default OptionalPP<T> minByL(ToLongFunction<T> sortProperty)  { return min(Comparator.comparingLong(sortProperty)); }
	default OptionalPP<T> minByD(ToDoubleFunction<T> sortProperty){ return min(Comparator.comparingDouble(sortProperty)); }
	default <U extends Comparable<? super U>> OptionalPP<T> minBy(Function<T, U> sortProperty){
		return min(Comparator.comparing(sortProperty));
	}
	default OptionalPP<T> min(){
		//noinspection unchecked
		return min((a, b) -> ((Comparable<T>)a).compareTo(b));
	}
	default OptionalPP<T> min(Comparator<? super T> comparator){
		return reduce(BinaryOperator.minBy(comparator));
	}
	
	default OptionalPP<T> maxByI(ToIntFunction<T> sortProperty)   { return max(Comparator.comparingInt(sortProperty)); }
	default OptionalPP<T> maxByL(ToLongFunction<T> sortProperty)  { return max(Comparator.comparingLong(sortProperty)); }
	default OptionalPP<T> maxByD(ToDoubleFunction<T> sortProperty){ return max(Comparator.comparingDouble(sortProperty)); }
	default <U extends Comparable<? super U>> OptionalPP<T> maxBy(Function<T, U> sortProperty){
		return max(Comparator.comparing(sortProperty));
	}
	default OptionalPP<T> max(){
		//noinspection unchecked
		return max((a, b) -> ((Comparable<T>)a).compareTo(b));
	}
	default OptionalPP<T> max(Comparator<? super T> comparator){
		return reduce(BinaryOperator.maxBy(comparator));
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
		OptionalInt  size = tryGetSize();
		ArrayList<T> res;
		if(size.isPresent()){
			var siz = size.getAsInt();
			if(siz == 0) return new ArrayList<>();
			res = new ArrayList<>(siz);
		}else res = new ArrayList<>();
		
		for(T t : this){
			res.add(t);
		}
		assert size.isEmpty() || size.getAsInt() == res.size() : size.getAsInt() + "!=" + res.size();
		return res;
	}
	
	default <E> List<E> toModList(Function<T, E> map){
		return map(map).toModList();
	}
	default List<T> toModList(){
		return toArrayList();
	}
	
	default <E> List<E> toList(Function<T, E> map){
		return map(map).toList();
	}
	
	default List<T> toList(){
		OptionalInt  size = tryGetSize();
		ArrayList<T> res;
		
		var iter = iterator();
		if(size.isPresent()){
			var siz = size.getAsInt();
			switch(siz){
				case 0 -> { return List.of(); }
				case 1 -> { return List.of(iter.next()); }
				case 2 -> { return List.of(iter.next(), iter.next()); }
				case 3 -> { return List.of(iter.next(), iter.next(), iter.next()); }
				case 4 -> { return List.of(iter.next(), iter.next(), iter.next(), iter.next()); }
			}
			res = new ArrayList<>(siz);
		}else res = new ArrayList<>();
		
		while(iter.hasNext()){
			T t = iter.next();
			res.add(t);
		}
		return List.copyOf(res);
	}
	
	default <T1> T1[] toArray(IntFunction<T1[]> ctor){
		var  size = tryGetSize().orElse(8);
		T1[] res  = ctor.apply(size);
		if(size == 0) return res;
		int siz = 0;
		for(T t : this){
			if(res.length == siz) res = Utils.growArr(res);
			res[siz++] = (T1)t;
		}
		if(res.length == siz) return res;
		return Arrays.copyOf(res, siz);
	}
	
	default <E> Set<E> toSet(Function<T, E> map){
		return map(map).toSet();
	}
	default Set<T> toSet(){
		return Set.copyOf(toModList());
	}
	default <E> Set<E> toModSet(Function<T, E> map){
		return map(map).toModSet();
	}
	default Set<T> toModSet(){
		OptionalInt size = tryGetSize();
		HashSet<T>  res;
		if(size.isPresent()){
			var siz = size.getAsInt();
			if(siz == 0) return new HashSet<>();
			res = HashSet.newHashSet(siz);
		}else res = new HashSet<>();
		for(T t : this){
			res.add(t);
		}
		if(size.isPresent() && size.getAsInt()>0 && size.getAsInt()>=res.size()*2){
			res = new HashSet<>(res);
		}
		return res;
	}
	
	default <K, V> Map<K, V> toMap(Function<T, K> key, Function<T, V> value){
		int size = tryGetSize().orElse(8);
		var iter = size>0? iterator() : null;
		T   e;
		return switch(size){
			case 0 -> Map.of();
			case 1 -> Map.of(key.apply((e = iter.next())), value.apply(e));
			case 2 -> Map.of(
				key.apply((e = iter.next())), value.apply(e),
				key.apply((e = iter.next())), value.apply(e)
			);
			case 3 -> Map.of(
				key.apply((e = iter.next())), value.apply(e),
				key.apply((e = iter.next())), value.apply(e),
				key.apply((e = iter.next())), value.apply(e)
			);
			case 4 -> Map.of(
				key.apply((e = iter.next())), value.apply(e),
				key.apply((e = iter.next())), value.apply(e),
				key.apply((e = iter.next())), value.apply(e),
				key.apply((e = iter.next())), value.apply(e)
			);
			default -> {
				var arr = new Map.Entry[size];
				int siz = 0;
				while(iter.hasNext()){
					if(arr.length == siz) arr = Utils.growArr(arr);
					arr[siz++] = Map.entry(key.apply((e = iter.next())), value.apply(e));
				}
				if(arr.length != siz) arr = Arrays.copyOf(arr, siz);
				yield Map.ofEntries(arr);
			}
		};
	}
	default <K, V> Map<K, V> toModMap(Function<T, Map.Entry<K, V>> entry){
		return map(entry).toModMap(Map.Entry::getKey, Map.Entry::getValue);
	}
	default <K, V> Map<K, V> toModMap(Function<T, K> key, Function<T, V> value){
		OptionalInt   size = tryGetSize();
		HashMap<K, V> res;
		if(size.isPresent()){
			var siz = size.getAsInt();
			if(siz == 0) return new HashMap<>();
			res = HashMap.newHashMap(siz);
		}else res = new HashMap<>();
		return toModMap(res, key, value);
	}
	
	default <K, V, M extends Map<K, V>> M toModMap(M dest, Function<T, K> key, Function<T, V> value){
		for(T t : this){
			K k;
			if(dest.put((k = key.apply(t)), value.apply(t)) != null){
				throw new IllegalStateException("Duplicate key of: " + k);
			}
		}
		return dest;
	}
	
	default <K> Map<K, Integer> toGroupingSizes(Function<T, K> key){
		var res = new HashMap<K, Integer>();
		for(T t : this){
			res.compute(key.apply(t), (k, v) -> v == null? 1 : v + 1);
		}
		return res;
	}
	default <K> Map<K, List<T>> toGrouping(Function<T, K> key){
		var res = new HashMap<K, List<T>>();
		for(T t : this){
			res.computeIfAbsent(key.apply(t), k -> new ArrayList<>()).add(t);
		}
		return res;
	}
	
	
	default PPCollection<T> asCollection(){
		return new PPCollection<>(this, tryGetSize());
	}
	default PPBakedSequence<T> bake(){
		//noinspection unchecked
		return new PPBakedSequence<>((T[])toArray(Object[]::new));
	}
	
	default String joinAsStr()                                              { return joinAsStr("", "", "", null); }
	default String joinAsStr(String delimiter)                              { return joinAsStr(delimiter, "", "", null); }
	default String joinAsStr(String delimiter, String prefix, String suffix){ return joinAsStr(delimiter, prefix, suffix, null); }
	
	default String joinAsStr(Function<T, String> toString)                  { return joinAsStr("", "", "", toString); }
	default String joinAsStr(String delimiter, Function<T, String> toString){ return joinAsStr(delimiter, "", "", toString); }
	default String joinAsStr(String delimiter, String prefix, String suffix, Function<T, String> toString){
		var iter = iterator();
		if(!iter.hasNext()) return prefix.isEmpty() && suffix.isEmpty()? "" : prefix + suffix;
		return strLoop(delimiter, prefix, suffix, toString, iter);
	}
	
	default Optional<String> joinAsOptionalStr()                                              { return joinAsOptionalStr("", "", "", null); }
	default Optional<String> joinAsOptionalStr(String delimiter)                              { return joinAsOptionalStr(delimiter, "", "", null); }
	default Optional<String> joinAsOptionalStr(Function<T, String> toString)                  { return joinAsOptionalStr("", "", "", toString); }
	default Optional<String> joinAsOptionalStr(String delimiter, Function<T, String> toString){ return joinAsOptionalStr(delimiter, "", "", toString); }
	/**
	 * NOTE: The prefix and suffix will only be included if there is any element!
	 */
	default Optional<String> joinAsOptionalStr(String delimiter, String prefix, String suffix){ return joinAsOptionalStr(delimiter, prefix, suffix, null); }
	/**
	 * NOTE: The prefix and suffix will only be included if there is any element!
	 */
	default Optional<String> joinAsOptionalStr(String delimiter, String prefix, String suffix, Function<T, String> toString){
		var iter = iterator();
		if(!iter.hasNext()) return Optional.empty();
		var res = strLoop(delimiter, prefix, suffix, toString, iter);
		return Optional.of(res);
	}
	
	private static <T> String strLoop(String delimiter, String prefix, String suffix, Function<T, String> toString, Iterator<T> iter){
		StringJoiner result;
		{
			var first    = iter.next();
			var firstStr = toStr(first, toString);
			
			if(!iter.hasNext()){
				if(prefix.length() == 0 && suffix.length() == 0){
					return firstStr;
				}
				return prefix + firstStr + suffix;
			}
			
			result = new StringJoiner(delimiter, prefix, suffix);
			result.add(firstStr);
		}
		
		do{
			var element = iter.next();
			var eString = toStr(element, toString);
			result.add(eString);
		}while(iter.hasNext());
		
		return result.toString();
	}
	
	private static <T> String toStr(T val, Function<T, String> toString){
		if(toString == null){
			return Objects.toString(val);
		}
		return toString.apply(val);
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
	
	default boolean noneIs(T value){ return !anyIs(value); }
	default boolean anyIsNot(T value){
		for(T t : this){
			if(t != value) return true;
		}
		return false;
	}
	default boolean anyIs(T value){
		for(T t : this){
			if(t == value) return true;
		}
		return false;
	}
	default boolean noneEquals(T value){ return !anyEquals(value); }
	default boolean anyEquals(T value){
		for(T t : this){
			if(Objects.equals(t, value)){
				return true;
			}
		}
		return false;
	}
	
	default IterablePP<T> nonNullProps(Function<T, ?> property){
		return new Iters.DefaultIterable<>(){
			@Override
			public Iterator<T> iterator(){
				var src = IterablePP.this.iterator();
				return new Iters.FindingNonNullIterator<>(){
					@Override
					protected T doNext(){
						while(true){
							if(!src.hasNext()) return null;
							var element = src.next();
							if(element == null) continue;
							if(property.apply(element) == null) continue;
							return element;
						}
					}
				};
			}
		};
	}
	default IterablePP<T> nonNulls(){
		return new Iters.DefaultIterable<>(){
			@Override
			public Iterator<T> iterator(){
				var src = IterablePP.this.iterator();
				return new Iters.FindingNonNullIterator<>(){
					@Override
					protected T doNext(){
						while(true){
							if(!src.hasNext()) return null;
							var element = src.next();
							if(element == null) continue;
							return element;
						}
					}
				};
			}
		};
	}
	
	default IterablePP<T> filter(Predicate<T> filter){
		return new Iters.DefaultIterable<>(){
			@Override
			public Iterator<T> iterator(){
				var src = IterablePP.this.iterator();
				return new Iters.FindingIterator<>(){
					@Override
					protected boolean doNext(){
						while(true){
							if(!src.hasNext()) return false;
							T t = src.next();
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
	
	default IterablePP.SizedPP<T> sortedByD(ToDoubleFunction<T> comparator)                            { return sorted(Comparator.comparingDouble(comparator)); }
	default IterablePP.SizedPP<T> sortedByI(ToIntFunction<T> comparator)                               { return sorted(Comparator.comparingInt(comparator)); }
	default IterablePP.SizedPP<T> sortedByL(ToLongFunction<T> comparator)                              { return sorted(Comparator.comparingLong(comparator)); }
	default <U extends Comparable<? super U>> IterablePP.SizedPP<T> sortedBy(Function<T, U> comparator){ return sorted(Comparator.comparing(comparator)); }
	default IterablePP.SizedPP<T> sorted(){
		//noinspection unchecked
		return sorted((Comparator<T>)Comparator.naturalOrder());
	}
	default IterablePP.SizedPP<T> sorted(Comparator<T> comparator){
		return new SizedPP.Default<>(){
			@Override
			public OptionalInt getSize(){ return SizedPP.tryGet(IterablePP.this); }
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
		return new Iters.DefaultIterable<>(){
			@Override
			public Iterator<T> iterator(){
				var src = IterablePP.this.iterator();
				return new Iters.FindingIterator<>(){
					private final Set<T> seen = new HashSet<>();
					@Override
					protected boolean doNext(){
						while(true){
							if(!src.hasNext()) return false;
							T t = src.next();
							if(seen.add(t)){
								reportFound(t);
								return true;
							}
						}
					}
				};
			}
		};
	}
	
	default <L> IterablePP.SizedPP<L> flatMapArray(Function<T, L[]> flatten){
		return new SizedPP.Default<>(){
			@Override
			public OptionalInt getSize(){
				int size = 0;
				for(T t : IterablePP.this){
					var lSize = size + (long)flatten.apply(t).length;
					if(lSize>Integer.MAX_VALUE) return OptionalInt.empty();
					size = (int)lSize;
				}
				return OptionalInt.of(size);
			}
			@Override
			public Iterator<L> iterator(){
				var src = IterablePP.this.iterator();
				return new Iters.FindingIterator<>(){
					private L[] arr;
					private int i;
					@Override
					protected boolean doNext(){
						while(true){
							if(arr == null || arr.length == i){
								if(!src.hasNext()) return false;
								arr = flatten.apply(src.next());
								i = 0;
								continue;
							}
							reportFound(arr[i++]);
							return true;
						}
					}
				};
			}
		};
	}
	default <L> IterablePP.SizedPP<L> flatMap(Function<T, Iterable<L>> flatten){
		return new SizedPP.Default<>(){
			@Override
			public OptionalInt getSize(){
				int size = 0;
				for(T t : IterablePP.this){
					var sizO = switch(flatten.apply(t)){
						case Collection<?> c -> OptionalInt.of(c.size());
						case SizedPP<?> s -> s.getSize();
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
			public Iterator<L> iterator(){
				var src = IterablePP.this.iterator();
				return new Iters.FindingIterator<>(){
					private Iterator<L> flat;
					@Override
					protected boolean doNext(){
						while(true){
							if(flat == null || !flat.hasNext()){
								if(!src.hasNext()) return false;
								flat = flatten.apply(src.next()).iterator();
								continue;
							}
							reportFound(flat.next());
							return true;
						}
					}
				};
			}
		};
	}
	default <L> IterablePP<L> flatIterators(Function<T, Iterator<L>> flatten){
		return new Iters.DefaultIterable<>(){
			@Override
			public Iterator<L> iterator(){
				var src = IterablePP.this.iterator();
				return new Iters.FindingIterator<>(){
					private Iterator<L> flat;
					@Override
					protected boolean doNext(){
						while(true){
							if(flat == null || !flat.hasNext()){
								if(!src.hasNext()) return false;
								flat = flatten.apply(src.next());
								continue;
							}
							reportFound(flat.next());
							return true;
						}
					}
				};
			}
		};
	}
	default <L> IterablePP<L> flatOptionals(Function<T, Optional<L>> map){
		return new Iters.DefaultIterable<>(){
			@Override
			public Iterator<L> iterator(){
				var src = IterablePP.this.iterator();
				return new Iters.FindingNonNullIterator<>(){
					@Override
					protected L doNext(){
						while(true){
							if(!src.hasNext()) return null;
							var o = map.apply(src.next());
							if(o.isEmpty()) continue;
							return o.get();
						}
					}
				};
			}
		};
	}
	default <L> IterablePP<L> flatOptionalsPP(Function<T, OptionalPP<L>> map){
		return flatOptionals(v -> map.apply(v).opt());
	}
	
	default IterableLongPP flatMapToLong(Function<T, IterableLongPP> flatten){
		return new Iters.DefaultLongIterable(){
			@Override
			public LongIterator iterator(){
				var src = IterablePP.this.iterator();
				return new Iters.FindingLongIterator(){
					private LongIterator flat;
					@Override
					protected boolean doNext(){
						while(true){
							if(flat == null || !flat.hasNext()){
								if(!src.hasNext()) return false;
								flat = flatten.apply(src.next()).iterator();
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
	default IterableIntPP flatMapToInt(Function<T, IterableIntPP> flatten){
		return new Iters.DefaultIntIterable(){
			@Override
			public IntIterator iterator(){
				var src = IterablePP.this.iterator();
				return new Iters.FindingIntIterator(){
					private IntIterator flat;
					@Override
					protected boolean doNext(){
						while(true){
							if(flat == null || !flat.hasNext()){
								if(!src.hasNext()) return false;
								flat = flatten.apply(src.next()).iterator();
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
	
	default <L> IterablePP<L> instancesOf(Class<L> type){
		return new Iters.DefaultIterable<>(){
			@Override
			public Iterator<L> iterator(){
				var src = IterablePP.this.iterator();
				return new Iters.FindingNonNullIterator<>(){
					@Override
					protected L doNext(){
						while(true){
							if(!src.hasNext()) return null;
							var o = src.next();
							if(!type.isInstance(o)) continue;
							return type.cast(o);
						}
					}
				};
			}
		};
	}
	default IterablePP<T> instancesOf(Class<?> type, Function<T, ?> element){
		return filter(e -> type.isInstance(element.apply(e)));
	}
	
	default IterablePP.SizedPP<T> mapIfNot(Predicate<T> selector, Function<T, T> mapper){ return mapIf(Predicate.not(selector), mapper); }
	default IterablePP.SizedPP<T> mapIf(Predicate<T> selector, Function<T, T> mapper){
		return new SizedPP.Default<>(){
			@Override
			public OptionalInt getSize(){ return SizedPP.tryGet(IterablePP.this); }
			@Override
			public Iterator<T> iterator(){
				var src = IterablePP.this.iterator();
				return new Iterator<>(){
					@Override
					public boolean hasNext(){
						return src.hasNext();
					}
					@Override
					public T next(){
						T unmapped = src.next();
						if(selector.test(unmapped)){
							return mapper.apply(unmapped);
						}
						return unmapped;
					}
				};
			}
		};
	}
	
	default <L> IterablePP.SizedPP<L> map(Function<T, L> mapper){
		return new SizedPP.Default<>(){
			@Override
			public OptionalInt getSize(){ return SizedPP.tryGet(IterablePP.this); }
			@Override
			public Iterator<L> iterator(){
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
			}
		};
	}
	
	default IterableLongPP mapToLong(){
		return new Iters.DefaultLongIterable(){
			@Override
			public LongIterator iterator(){
				var iter = IterablePP.this.iterator();
				return new LongIterator(){
					@Override
					public boolean hasNext(){ return iter.hasNext(); }
					@Override
					public long nextLong(){
						return (long)iter.next();
					}
				};
			}
		};
	}
	default IterableLongPP mapToLong(FunctionOL<T> mapper){
		return new Iters.DefaultLongIterable(){
			@Override
			public LongIterator iterator(){
				var iter = IterablePP.this.iterator();
				return new LongIterator(){
					@Override
					public boolean hasNext(){ return iter.hasNext(); }
					@Override
					public long nextLong(){
						return mapper.apply(iter.next());
					}
				};
			}
		};
	}
	default IterableIntPP mapToInt(){
		return new Iters.DefaultIntIterable(){
			@Override
			public IntIterator iterator(){
				var iter = IterablePP.this.iterator();
				return new IntIterator(){
					@Override
					public boolean hasNext(){ return iter.hasNext(); }
					@Override
					public int nextInt(){
						return (int)iter.next();
					}
				};
			}
		};
	}
	default IterableIntPP mapToInt(FunctionOI<T> mapper){
		return new Iters.DefaultIntIterable(){
			@Override
			public IntIterator iterator(){
				var iter = IterablePP.this.iterator();
				return new IntIterator(){
					@Override
					public boolean hasNext(){ return iter.hasNext(); }
					@Override
					public int nextInt(){
						return mapper.apply(iter.next());
					}
				};
			}
		};
	}
	
	default IterablePP.SizedPP<T> skip(long count){
		if(count<0) throw new IllegalArgumentException("count cannot be negative");
		return new SizedPP.Default<>(){
			@Override
			public OptionalInt getSize(){
				var s = SizedPP.tryGet(IterablePP.this);
				if(s.isEmpty()) return OptionalInt.empty();
				return OptionalInt.of((int)Math.max(0, s.getAsInt() - count));
			}
			@Override
			public Iterator<T> iterator(){
				var iter = IterablePP.this.iterator();
				for(long i = 0; i<count; i++){
					if(!iter.hasNext()) break;
					iter.next();
				}
				return iter;
			}
		};
	}
	
	default IterablePP.SizedPP<T> limit(int maxLen){
		if(maxLen<0) throw new IllegalArgumentException("maxLen cannot be negative");
		if(maxLen == 0) return Iters.of();
		return new SizedPP.Default<>(){
			@Override
			public OptionalInt getSize(){
				var s = SizedPP.tryGet(IterablePP.this);
				if(s.isEmpty()) return OptionalInt.empty();
				return OptionalInt.of(Math.min(s.getAsInt(), maxLen));
			}
			@Override
			public Iterator<T> iterator(){
				var src = IterablePP.this.iterator();
				return new Iterator<>(){
					private int count;
					
					@Override
					public boolean hasNext(){
						return maxLen>count && src.hasNext();
					}
					@Override
					public T next(){
						if(maxLen<++count) throw new NoSuchElementException();
						return src.next();
					}
				};
			}
		};
	}
	
	default IterablePP<T> takeWhile(Predicate<T> condition){
		return new Iters.DefaultIterable<>(){
			@Override
			public Iterator<T> iterator(){
				var iter = IterablePP.this.iterator();
				return new Iterator<>(){
					private T       val;
					private boolean hasVal, triggered;
					
					private boolean calcNext(){
						if(triggered || !iter.hasNext()) return false;
						var v = iter.next();
						if(!condition.test(v)){
							triggered = true;
							return false;
						}
						val = v;
						return true;
					}
					
					@Override
					public boolean hasNext(){
						return hasVal || (hasVal = calcNext());
					}
					@Override
					public T next(){
						if(!hasVal && !calcNext()) throw new NoSuchElementException();
						hasVal = false;
						return val;
					}
				};
			}
		};
	}
	default IterablePP<T> dropWhile(Predicate<T> condition){
		return new Iters.DefaultIterable<>(){
			@Override
			public Iterator<T> iterator(){
				var iter = IterablePP.this.iterator();
				return new Iterator<>(){
					private T       val;
					private boolean hasVal, dropped;
					
					private void drop(){
						dropped = true;
						while(iter.hasNext()){
							var next = iter.next();
							if(condition.test(next)){
								continue;
							}
							val = next;
							hasVal = true;
							break;
						}
					}
					
					@Override
					public boolean hasNext(){
						if(dropped){
							return hasVal || iter.hasNext();
						}
						drop();
						return hasVal;
					}
					@Override
					public T next(){
						if(!dropped) drop();
						if(hasVal){
							hasVal = false;
							var v = val;
							val = null;
							return v;
						}
						return iter.next();
					}
				};
			}
		};
	}
	
	interface Enumerator<T, R>{
		R enumerate(int index, T value);
	}
	
	interface EnumeratorL<T, R>{
		R enumerate(long index, T value);
	}
	
	record Idx<T>(int index, T val) implements Map.Entry<Integer, T>{
		@Override
		public Integer getKey(){ return index; }
		@Override
		public T getValue(){ return val; }
		@Override
		public T setValue(T value){ throw new UnsupportedOperationException(); }
	}
	
	record Ldx<T>(long index, T val) implements Map.Entry<Long, T>{
		@Override
		public Long getKey(){ return index; }
		@Override
		public T getValue(){ return val; }
		@Override
		public T setValue(T value){ throw new UnsupportedOperationException(); }
	}
	
	default IterablePP.SizedPP<Idx<T>> enumerate(){
		return new SizedPP.Default<>(){
			@Override
			public OptionalInt getSize(){ return SizedPP.tryGet(IterablePP.this); }
			@Override
			public Iterator<Idx<T>> iterator(){
				var src = IterablePP.this.iterator();
				return new Iterator<>(){
					private int index;
					@Override
					public boolean hasNext(){
						return src.hasNext();
					}
					@Override
					public Idx<T> next(){
						preIncrementInt(index);
						return new Idx<>(index++, src.next());
					}
				};
			}
		};
	}
	
	default IterablePP.SizedPP<Ldx<T>> enumerateL(){
		return new SizedPP.Default<>(){
			@Override
			public OptionalInt getSize(){ return SizedPP.tryGet(IterablePP.this); }
			@Override
			public Iterator<Ldx<T>> iterator(){
				var src = IterablePP.this.iterator();
				return new Iterator<>(){
					private long index;
					@Override
					public boolean hasNext(){
						return src.hasNext();
					}
					@Override
					public Ldx<T> next(){
						preIncrementLong(index);
						return new Ldx<>(index++, src.next());
					}
				};
			}
		};
	}
	
	default <R> IterablePP.SizedPP<R> enumerate(Enumerator<T, R> enumerator){
		return new SizedPP.Default<>(){
			@Override
			public OptionalInt getSize(){ return SizedPP.tryGet(IterablePP.this); }
			@Override
			public Iterator<R> iterator(){
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
			}
		};
	}
	default <R> IterablePP.SizedPP<R> enumerateL(EnumeratorL<T, R> enumerator){
		return new SizedPP.Default<>(){
			@Override
			public OptionalInt getSize(){ return SizedPP.tryGet(IterablePP.this); }
			@Override
			public Iterator<R> iterator(){
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
			}
		};
	}
	
	private static void preIncrementLong(long index){
		if(index == Long.MAX_VALUE) throw new IllegalStateException("Too many elements");
	}
	private static void preIncrementInt(int num){
		if(num == Integer.MAX_VALUE) throw new IllegalStateException("Too many elements");
	}
	
	private OptionalInt tryGetSize(){
		return SizedPP.tryGet(this);
	}
}
