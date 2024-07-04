package com.lapissea.dfs.utils;

import com.lapissea.util.TextUtil;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.stream.Stream;

public final class PPCollection<T> implements IterablePP<T>, Collection<T>{
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
		if(empty == TRUE) return (Iterator<T>)Iters.EMPTY_ITER;
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
