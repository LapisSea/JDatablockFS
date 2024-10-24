package com.lapissea.dfs.utils.iterableplus;

import com.lapissea.util.TextUtil;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.stream.Stream;

public final class PPCollection<T> implements IterablePP.SizedPP<T>, Collection<T>{
	private final IterablePP<T> source;
	private       List<T>       computed;
	private       Set<T>        computedSet;
	private       byte          empty = UNKNOWN;
	private       int           size  = -1;
	
	private Iterator<T> madeIter;
	
	private static final byte TRUE = 1, FALSE = 0, UNKNOWN = 2;
	
	public PPCollection(IterablePP<T> source, OptionalInt size){
		this(source);
		if(size.isPresent()){
			this.size = size.getAsInt();
			empty = this.size == 0? TRUE : FALSE;
		}
	}
	public PPCollection(IterablePP<T> source){ this.source = source; }
	
	private Iterator<T> sourceIter(){
		var mi = madeIter;
		if(mi != null){
			madeIter = null;
			return mi;
		}
		
		return source.iterator();
	}
	
	private List<T> compute(){
		var c = computed;
		if(c != null) return c;
		if(empty == TRUE) return computed = List.of();
		c = toModList();
		if(recordEmpty(c.isEmpty())){
			c = List.of();
		}
		return computed = c;
	}
	
	private Set<T> computeSet(){
		var c = computedSet;
		if(c != null) return c;
		if(empty == TRUE) return computedSet = Set.of();
		if(computed != null) return computedSet = new HashSet<>(computed);
		return computedSet = toModSet();
	}
	
	@Override
	public Iterator<T> iterator(){
		var c = computed;
		if(c != null) return c.iterator();
		if(empty == TRUE) return (Iterator<T>)Iters.EMPTY_ITER;
		var iter = sourceIter();
		if(empty == UNKNOWN) recordEmpty(!iter.hasNext());
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
		if(size != -1) return size;
		return size = compute().size();
	}
	@Override
	public Stream<T> stream(){
		if(computed != null) return computed.stream();
		return source.stream();
	}
	@Override
	public boolean isEmpty(){
		if(empty != UNKNOWN) return empty == TRUE;
		if(computed != null) return recordEmpty(computed.isEmpty());
		if(computedSet != null) return recordEmpty(computedSet.isEmpty());
		var iter    = sourceIter();
		var isEmpty = recordEmpty(!iter.hasNext());
		if(!isEmpty) conserveIter(iter);
		return isEmpty;
	}
	
	private void conserveIter(Iterator<T> iter){
		var first = iter.next();
		madeIter = new Iterator<>(){
			private boolean didFirst;
			@Override
			public boolean hasNext(){
				return !didFirst || iter.hasNext();
			}
			@Override
			public T next(){
				if(!didFirst){
					didFirst = true;
					return first;
				}
				return iter.next();
			}
		};
	}
	private boolean recordEmpty(boolean empty){
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
	@Override
	public OptionalInt getSize(){
		if(empty == TRUE) return OptionalInt.of(0);
		if(size != -1) return OptionalInt.of(size);
		if(computed != null) return OptionalInt.of(computed.size());
		return OptionalInt.empty();
	}
}
