package com.lapissea.dfs.utils.iterableplus;

import com.lapissea.dfs.Utils;
import com.lapissea.dfs.utils.OptionalPP;
import com.lapissea.util.TextUtil;

import java.util.AbstractMap;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.LongFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.random.RandomGenerator;

@SuppressWarnings("unused")
public final class Iters{
	
	public abstract static class DefaultIterable<T> implements IterablePP<T>{
		@Override
		public String toString(){
			return Iters.toString(this);
		}
	}
	
	public abstract static class DefaultLongIterable implements IterableLongPP{
		@Override
		public String toString(){
			return Iters.toString(box());
		}
	}
	
	public abstract static class DefaultIntIterable implements IterableIntPP{
		@Override
		public String toString(){
			return Iters.toString(box());
		}
	}
	
	abstract static class FindingIterator<T> implements Iterator<T>{
		
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
	
	abstract static class FindingNonNullIterator<T> implements Iterator<T>{
		
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
	
	abstract static class FindingLongIterator implements LongIterator{
		
		private long    next;
		private boolean hasData;
		
		protected void reportFound(long val){
			hasData = true;
			next = val;
		}
		
		protected abstract boolean doNext();
		
		@Override
		public boolean hasNext(){
			return hasData || doNext();
		}
		@Override
		public long nextLong(){
			if(!hasData && !doNext()) throw new NoSuchElementException();
			hasData = false;
			return next;
		}
	}
	
	abstract static class FindingIntIterator implements IntIterator{
		
		private int     next;
		private boolean hasData;
		
		protected void reportFound(int val){
			hasData = true;
			next = val;
		}
		
		protected abstract boolean doNext();
		
		@Override
		public boolean hasNext(){
			return hasData || doNext();
		}
		@Override
		public int nextInt(){
			if(!hasData && !doNext()) throw new NoSuchElementException();
			hasData = false;
			return next;
		}
	}
	
	private record CollectionIterable<T>(Collection<T> data) implements IterablePP.SizedPP<T>{
		@Override
		public Iterator<T> iterator(){
			return data.iterator();
		}
		@Override
		public OptionalInt getSize(){
			return OptionalInt.of(data.size());
		}
		@Override
		public String toString(){
			return data.toString();
		}
		@Override
		public List<T> toList(){
			return List.copyOf(data);
		}
		@Override
		public <T1> T1[] toArray(IntFunction<T1[]> ctor){ return data.toArray(ctor); }
	}
	
	private record ArrayIterable<T>(T[] data) implements IterablePP.SizedPP<T>{
		private static final class Iter<T> implements Iterator<T>{
			private final T[] data;
			private       int index;
			public Iter(T[] data){ this.data = data; }
			@Override
			public boolean hasNext(){ return index<data.length; }
			@Override
			public T next(){
				var i = index++;
				if(i == data.length) throw new NoSuchElementException();
				return data[i];
			}
		}
		@Override
		public Iterator<T> iterator(){
			return new Iter<>(data);
		}
		@Override
		public OptionalInt getSize(){
			return OptionalInt.of(data.length);
		}
		@Override
		public String toString(){
			return TextUtil.unknownArrayToString(data);
		}
		@Override
		public List<T> toList(){
			return List.of(data);
		}
		@SuppressWarnings("SuspiciousSystemArraycopy")
		@Override
		public <T1> T1[] toArray(IntFunction<T1[]> ctor){
			var res = ctor.apply(data.length);
			System.arraycopy(data, 0, res, 0, data.length);
			return res;
		}
	}
	
	private record SingleIterable<T>(T element) implements IterablePP.SizedPP<T>{
		private static final class Iter<T> implements Iterator<T>{
			private final T       element;
			private       boolean done;
			public Iter(T element){ this.element = element; }
			@Override
			public boolean hasNext(){ return !done; }
			@Override
			public T next(){
				if(done) throw new NoSuchElementException();
				done = true;
				return element;
			}
		}
		
		@Override
		public Iterator<T> iterator(){
			return new Iter<>(element);
		}
		@Override
		public OptionalInt getSize(){
			return OptionalInt.of(1);
		}
		@Override
		public String toString(){
			return "[" + element + "]";
		}
		@Override
		public List<T> toList(){
			return List.of(element);
		}
		@SuppressWarnings("unchecked")
		@Override
		public <T1> T1[] toArray(IntFunction<T1[]> ctor){
			var res = ctor.apply(1);
			res[0] = (T1)element;
			return res;
		}
	}
	
	private static final class FlatArrIterator<T> implements Iterator<T>{
		private final Iterable<? extends T>[] iterables;
		
		private Iterator<? extends T> iter;
		private int                   pos;
		public FlatArrIterator(Iterable<? extends T>[] iterables){ this.iterables = Objects.requireNonNull(iterables); }
		
		@Override
		public boolean hasNext(){
			return iter != null && iter.hasNext() || findNext() != null;
		}
		private Iterator<? extends T> findNext(){
			while(pos<iterables.length){
				var i = iter = iterables[pos++].iterator();
				if(i.hasNext()) return i;
			}
			return null;
		}
		@Override
		public T next(){
			var i = iter;
			if((i == null || !i.hasNext()) &&
			   (i = findNext()) == null){
				throw new NoSuchElementException();
			}
			return i.next();
		}
	}
	
	static final Iterator<Object>           EMPTY_ITER = new Iterator<>(){
		@Override
		public boolean hasNext(){ return false; }
		@Override
		public Object next(){ return null; }
	};
	static final IterablePP.SizedPP<Object> EMPTY      = new IterablePP.SizedPP<>(){
		@Override
		public OptionalInt getSize(){
			return OptionalInt.of(0);
		}
		@Override
		public Iterator<Object> iterator(){
			return EMPTY_ITER;
		}
	};
	
	public static String toString(IterablePP<?> inst){
		return inst.joinAsStr(", ", "[", "]", TextUtil::toShortString);
	}
	
	public static <T> IterablePP<T> iterate(T seed, Predicate<? super T> hasNext, UnaryOperator<T> next){
		Objects.requireNonNull(next);
		Objects.requireNonNull(hasNext);
		return new Iters.DefaultIterable<>(){
			@Override
			public Iterator<T> iterator(){
				return new Iterator<>(){
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
		};
	}
	
	public static <T> IterablePP<T> generate(Supplier<Supplier<T>> generatorSup){
		return new Iters.DefaultIterable<>(){
			@Override
			public Iterator<T> iterator(){
				var generator = generatorSup.get();
				return new Iterator<>(){
					@Override
					public boolean hasNext(){ return true; }
					@Override
					public T next(){
						return generator.get();
					}
				};
			}
		};
	}
	
	public static <T> IterablePP<T> nullTerminated(Supplier<Supplier<T>> supplier){
		return new Iters.DefaultIterable<>(){
			@Override
			public Iterator<T> iterator(){
				var src = supplier.get();
				return new FindingNonNullIterator<>(){
					@Override
					protected T doNext(){
						return src.get();
					}
				};
			}
		};
	}
	
	public static IterableLongPP ofLongs(long element){
		return () -> new IterableLongPP.SingleIter(element);
	}
	public static IterableLongPP ofLongs(long... data){
		return switch(data.length){
			case 0 -> IterableLongPP.empty();
			case 1 -> ofLongs(data[0]);
			default -> () -> new IterableLongPP.ArrayIter(data);
		};
	}
	public static IterableIntPP ofInts(int element){
		return () -> new IterableIntPP.SingleIter(element);
	}
	public static IterableIntPP ofInts(int... data){
		return switch(data.length){
			case 0 -> IterableIntPP.empty();
			case 1 -> ofInts(data[0]);
			default -> () -> new IterableIntPP.ArrayIter(data);
		};
	}
	public static IterableLongPP range(long start, long endExclusive){
		if(start == endExclusive) return IterableLongPP.empty();
		if(endExclusive<start) throw new IllegalArgumentException("endExclusive<start");
		return () -> new LongIterator(){
			private long i = start;
			@Override
			public boolean hasNext(){
				return i<endExclusive;
			}
			@Override
			public long nextLong(){
				if(!hasNext()) throw new NoSuchElementException();
				return i++;
			}
		};
	}
	public static IterableIntPP range(int start, int endExclusive){
		if(start == endExclusive) return IterableIntPP.empty();
		if(endExclusive<start) throw new IllegalArgumentException("endExclusive<start");
		return () -> new IntIterator(){
			private int i = start;
			@Override
			public boolean hasNext(){
				return i<endExclusive;
			}
			@Override
			public int nextInt(){
				if(!hasNext()) throw new NoSuchElementException();
				return i++;
			}
		};
	}
	
	
	@SuppressWarnings("unchecked")
	public static <T> IterablePP.SizedPP<T> of(){ return (IterablePP.SizedPP<T>)EMPTY; }
	public static <T> IterablePP.SizedPP<T> of(T element){ return new SingleIterable<>(element); }
	@SafeVarargs
	public static <T> IterablePP.SizedPP<T> of(T... data){ return from(data); }
	
	public static IterableLongPP ofPresent(OptionalLong element)          { return element.isEmpty()? IterableLongPP.empty() : ofLongs(element.getAsLong()); }
	public static IterableLongPP ofPresent(OptionalLong... elements)      { return from(elements).filter(OptionalLong::isPresent).mapToLong(OptionalLong::getAsLong); }
	
	public static IterableIntPP ofPresent(OptionalInt... elements)        { return from(elements).filter(OptionalInt::isPresent).mapToInt(OptionalInt::getAsInt); }
	public static IterableIntPP ofPresent(OptionalInt element)            { return element.isEmpty()? IterableIntPP.empty() : ofInts(element.getAsInt()); }
	
	public static <T> IterablePP.SizedPP<T> ofPresent(Optional<T> element){ return element.isEmpty()? of() : new SingleIterable<>(element.get()); }
	@SafeVarargs
	public static <T> IterablePP<T> ofPresent(Optional<T>... data){ return data.length == 0? of() : new ArrayIterable<>(data).flatOptionals(Function.identity()); }
	
	public static <T> IterablePP.SizedPP<T> from(OptionalPP<T> element)             { return element.isEmpty()? of() : new SingleIterable<>(element.get()); }
	public static <T> IterablePP.SizedPP<T> from(Optional<T> element)               { return element.isEmpty()? of() : new SingleIterable<>(element.get()); }
	public static <T> IterablePP.SizedPP<T> from(Collection<T> data)                { return new CollectionIterable<>(data); }
	public static <T> IterablePP.SizedPP<T> from(T[] data)                          { return data.length == 0? of() : new ArrayIterable<>(data); }
	public static <T> IterablePP<T> from(Iterable<T> data)                          { return data instanceof Collection<T> c? new CollectionIterable<>(c) : data::iterator; }
	
	public static <V> IterablePP.SizedPP<V> values(Map<?, V> data)                  { return new CollectionIterable<>(data.values()); }
	public static <K> IterablePP.SizedPP<K> keys(Map<K, ?> data)                    { return new CollectionIterable<>(data.keySet()); }
	public static <K, V> IterablePP.SizedPP<Map.Entry<K, V>> entries(Map<K, V> data){ return new CollectionIterable<>(data.entrySet()); }
	
	public static <T> IterablePP.SizedPP<T> rangeMap(int fromInclusive, int toExclusive, IntFunction<T> mapping){
		if(fromInclusive>toExclusive) throw new IllegalArgumentException(fromInclusive + " > " + toExclusive);
		return new IterablePP.SizedPP.Default<>(){
			@Override
			public OptionalInt getSize(){
				return OptionalInt.of(toExclusive - fromInclusive);
			}
			@Override
			public Iterator<T> iterator(){
				return new Iterator<>(){
					private int val = fromInclusive;
					@Override
					public boolean hasNext(){
						return val<toExclusive;
					}
					@Override
					public T next(){
						return mapping.apply(val++);
					}
				};
			}
		};
	}
	
	public static <T> IterablePP.SizedPP<T> rangeMapL(long fromInclusive, long toExclusive, LongFunction<T> mapping){
		if(fromInclusive>toExclusive) throw new IllegalArgumentException(fromInclusive + " > " + toExclusive);
		return new IterablePP.SizedPP.Default<>(){
			@Override
			public OptionalInt getSize(){
				var size = toExclusive - fromInclusive;
				return Utils.longToOptInt(size);
			}
			@Override
			public Iterator<T> iterator(){
				return new Iterator<>(){
					private long val = fromInclusive;
					@Override
					public boolean hasNext(){
						return val<toExclusive;
					}
					@Override
					public T next(){
						return mapping.apply(val++);
					}
				};
			}
		};
	}
	
	public static <T> IterablePP.SizedPP<T> concat(Collection<T> a, Collection<T> b){
		var aEmpty = a.isEmpty();
		if(aEmpty || b.isEmpty()){
			var col = aEmpty? b : a;
			//noinspection unchecked
			return col instanceof IterablePP.SizedPP<?> i? (IterablePP.SizedPP<T>)i : from(col);
		}
		return new IterablePP.SizedPP.Default<>(){
			@Override
			public OptionalInt getSize(){
				var lSize = a.size() + (long)b.size();
				return Utils.longToOptInt(lSize);
			}
			@Override
			public Iterator<T> iterator(){
				return new Iterator<>(){
					private Iterator<T> iter = a.iterator();
					private boolean     aDone;
					
					private Iterator<T> nextIter(){
						if(aDone) return null;
						aDone = true;
						var it = iter = b.iterator();
						return it.hasNext()? it : null;
					}
					
					@Override
					public boolean hasNext(){
						return iter.hasNext() || nextIter() != null;
					}
					@Override
					public T next(){
						var it = iter;
						if(!it.hasNext() && (it = nextIter()) == null) throw new NoSuchElementException();
						return it.next();
					}
				};
			}
		};
	}
	
	@SafeVarargs
	@SuppressWarnings("varargs")
	public static <T> IterablePP.SizedPP<T> concat(Collection<? extends T>... iterables){
		if(iterables.length == 0) return of();
		return new IterablePP.SizedPP.Default<>(){
			@Override
			public OptionalInt getSize(){
				int size = 0;
				for(var col : iterables){
					var lSize = size + (long)col.size();
					if(lSize>Integer.MAX_VALUE) return OptionalInt.empty();
					size = (int)lSize;
				}
				return OptionalInt.of(size);
			}
			@Override
			public Iterator<T> iterator(){ return new FlatArrIterator<>(iterables); }
		};
	}
	
	@SafeVarargs
	@SuppressWarnings("varargs")
	public static <T> IterablePP.SizedPP<T> concat(Iterable<? extends T>... iterables){
		if(iterables.length == 0) return of();
		return new IterablePP.SizedPP.Default<>(){
			@Override
			public OptionalInt getSize(){
				int size = 0;
				for(var iter : iterables){
					int siz;
					switch(iter){
						case Collection<?> c -> siz = c.size();
						case SizedPP<?> s -> {
							var o = s.getSize();
							if(o.isPresent()) siz = o.getAsInt();
							else return OptionalInt.empty();
						}
						default -> { return OptionalInt.empty(); }
					}
					
					var lSize = size + (long)siz;
					if(lSize>Integer.MAX_VALUE) return OptionalInt.empty();
					size = (int)lSize;
				}
				return OptionalInt.of(size);
			}
			@Override
			public Iterator<T> iterator(){ return new FlatArrIterator<>(iterables); }
		};
	}
	
	public static <T> IterablePP.SizedPP<T> concat1N(T first, Collection<? extends T> extra){ return concat(List.of(first), extra); }
	public static <T> IterablePP.SizedPP<T> concat1N(T first, Iterable<? extends T> extra)  { return concat(Iters.of(first), extra); }
	public static <T> IterablePP.SizedPP<T> concatN1(Collection<? extends T> start, T last) { return concat(start, List.of(last)); }
	public static <T> IterablePP.SizedPP<T> concatN1(Iterable<? extends T> start, T last)   { return concat(start, Iters.of(last)); }
	
	public static <A, B> IterablePP.SizedPP<Map.Entry<A, B>> zip(Collection<A> a, Collection<B> b){
		return zip(a, b, AbstractMap.SimpleEntry::new);
	}
	public static <A, B, Zip> IterablePP.SizedPP<Zip> zip(Collection<A> a, Collection<B> b, BiFunction<A, B, Zip> zipper){
		int s;
		if((s = a.size()) != b.size()) throw new IllegalArgumentException("Collection sizes are not the same!");
		return new IterablePP.SizedPP.Default<>(){
			@Override
			public Iterator<Zip> iterator(){
				var ai = a.iterator();
				var bi = b.iterator();
				return new Iterator<>(){
					@Override
					public boolean hasNext(){
						return ai.hasNext();
					}
					@Override
					public Zip next(){
						boolean hna = ai.hasNext(), hnb = bi.hasNext();
						if(hna != hnb) throw new IllegalStateException("Zipped collections changed size!");
						if(!hna) throw new NoSuchElementException();
						
						return zipper.apply(ai.next(), bi.next());
					}
				};
			}
			@Override
			public OptionalInt getSize(){
				return OptionalInt.of(s);
			}
		};
	}
	
	public static IterableIntPP rand(RandomGenerator rand, int streamSize, int origin, int bound){
		if(streamSize<0) throw new IllegalArgumentException("Size must be positive!");
		return range(0, streamSize).map(i -> rand.nextInt(origin, bound));
	}
}
