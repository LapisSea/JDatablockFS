package com.lapissea.dfs.utils;

import com.lapissea.util.TextUtil;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.LongFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

public final class IterablePPs{
	
	public static final class PPCollection<T> implements IterablePP<T>, Collection<T>{
		private final IterablePP<T> source;
		private       List<T>       computed;
		private       Set<T>        computedSet;
		private       byte          empty = UNKNOWN;
		
		private static final byte TRUE = 1, FALSE = 0, UNKNOWN = 2;
		
		public PPCollection(IterablePP<T> source){ this.source = source; }
		
		private List<T> compute(){
			var c = computed;
			if(c != null) return c;
			if(empty == TRUE) return computed = List.of();
			c = collectToList();
			if(emp(c.isEmpty())){
				c = List.of();
			}
			return computed = c;
		}
		
		private Set<T> computeSet(){
			var c = computedSet;
			if(c != null) return c;
			if(empty == TRUE) return computedSet = Set.of();
			if(computed != null) return computedSet = new HashSet<>(computed);
			return computedSet = collectToSet();
		}
		
		@Override
		public Iterator<T> iterator(){
			var c = computed;
			if(c != null) return c.iterator();
			if(empty == TRUE) return (Iterator<T>)EMPTY_ITER;
			var iter = source.iterator();
			if(empty == UNKNOWN) emp(!iter.hasNext());
			return iter;
		}
		@Override
		public Object[] toArray(){
			return compute().toArray();
		}
		@Override
		public <T1> T1[] toArray(T1[] a){
			return compute().toArray(a);
		}
		@Override
		public boolean add(T t){ throw new UnsupportedOperationException(); }
		@Override
		public boolean remove(Object o){ throw new UnsupportedOperationException(); }
		@Override
		public boolean addAll(Collection<? extends T> c){ throw new UnsupportedOperationException(); }
		@Override
		public boolean removeAll(Collection<?> c){ throw new UnsupportedOperationException(); }
		@Override
		public boolean retainAll(Collection<?> c){ throw new UnsupportedOperationException(); }
		@Override
		public void clear(){ throw new UnsupportedOperationException(); }
		@Override
		public int size(){
			if(empty == TRUE) return 0;
			return compute().size();
		}
		@Override
		public Stream<T> stream(){
			if(computed != null) return computed.stream();
			return source.stream();
		}
		@Override
		public boolean isEmpty(){
			if(empty != UNKNOWN) return empty == TRUE;
			if(computed != null) return emp(computed.isEmpty());
			if(computedSet != null) return emp(computedSet.isEmpty());
			return emp(!source.iterator().hasNext());
		}
		private boolean emp(boolean empty){
			this.empty = empty? TRUE : FALSE;
			return empty;
		}
		@Override
		public <T1> T1[] toArray(IntFunction<T1[]> ctor){
			return compute().toArray(ctor);
		}
		@Override
		public boolean containsAll(Collection<?> c){
			return computeSet().containsAll(c);
		}
		@Override
		public boolean contains(Object o){
			return computeSet().contains(o);
		}
		@Override
		public String toString(){
			if(empty == TRUE) return "[]";
			if(computed != null) return TextUtil.toString(computed);
			return "[<?>]";
		}
	}
	
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
	
	static{
		TextUtil.CUSTOM_TO_STRINGS.register(IterablePP.class, (IterablePP<?> pp) -> {
			return toString(pp);
		});
	}
	
	public static String toString(IterablePP<?> inst){
		return inst.joinAsStr(", ", "[", "]", TextUtil::toShortString);
	}
	
	public static <T> IterablePP<T> nullTerminated(Supplier<Supplier<T>> supplier){
		return () -> new Iterator<T>(){
			private final Supplier<T> src = supplier.get();
			private       T           next;
			
			void calcNext(){
				next = src.get();
			}
			
			@Override
			public boolean hasNext(){
				if(next == null) calcNext();
				return next != null;
			}
			@Override
			public T next(){
				if(next == null){
					calcNext();
					if(next == null) throw new NoSuchElementException();
				}
				try{
					return next;
				}finally{
					next = null;
				}
			}
		};
	}
	
	private static final IterablePP<?> EMPTY      = of0();
	private static final Iterator<?>   EMPTY_ITER = new Iterator<>(){
		@Override
		public boolean hasNext(){
			return false;
		}
		@Override
		public Object next(){
			return null;
		}
	};
	
	public static <T> IterablePP<T> of(){
		return (IterablePP<T>)EMPTY;
	}
	
	public static IterableLongPP ofLongs(long... data){
		if(data.length == 0) return IterableLongPP.empty();
		return () -> new LongIterator(){
			int i = 0;
			@Override
			public boolean hasNext(){
				return i<data.length;
			}
			@Override
			public long nextLong(){
				return data[i++];
			}
		};
	}
	
	@SafeVarargs
	public static <T> IterablePP<T> of(T... data){
		if(data.length == 0) return of();
		return of0(data);
	}
	
	@SuppressWarnings("unchecked")
	private static <T> IterablePP<T> of0(T... data){
		return () -> {
			return new Iterator<>(){
				private int index;
				@Override
				public boolean hasNext(){
					return data.length>index;
				}
				@Override
				public T next(){
					return data[index++];
				}
			};
		};
	}
	
	public static <T> IterablePP<T> from(Collection<T> data){
		return data::iterator;
	}
	
	public static <V> IterablePP<V> values(Map<?, V> data){
		return data.values()::iterator;
	}
	public static <K> IterablePP<K> keys(Map<K, ?> data){
		return data.keySet()::iterator;
	}
	public static <K, V> IterablePP<Map.Entry<K, V>> entries(Map<K, V> data){
		return data.entrySet()::iterator;
	}
	
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
		return concat(IterablePPs.of(first), extra);
	}
}
