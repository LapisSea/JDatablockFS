package com.lapissea.dfs.utils.iterableplus;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.function.IntFunction;
import java.util.stream.Stream;

public final class PPBakedSequence<T> extends AbstractList<T> implements IterablePP.SizedPP<T>{
	private final T[] data;
	
	public PPBakedSequence(T[] data){ this.data = Objects.requireNonNull(data); }
	
	@Override
	public OptionalInt getSize(){
		return OptionalInt.of(data.length);
	}
	@Override
	public T get(int index){
		return data[index];
	}
	@Override
	public int size(){
		return data.length;
	}
	@Override
	public Stream<T> stream(){
		return Arrays.stream(data);
	}
	@Override
	public List<T> toList(){
		return List.of(data);
	}
	@SuppressWarnings("SuspiciousSystemArraycopy")
	@Override
	public <T1> T1[] toArray(IntFunction<T1[]> generator){
		var arr = generator.apply(data.length);
		System.arraycopy(data, 0, arr, 0, arr.length);
		return arr;
	}
	@Override
	public T getFirst(){
		return data[0];
	}
	@Override
	public int count(){ return data.length; }
	@Override
	public long countL(){ return data.length; }
}
