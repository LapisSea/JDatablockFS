package com.lapissea.cfs.objects.collections;

import com.lapissea.cfs.IterablePP;

import java.io.IOException;
import java.util.Iterator;
import java.util.stream.LongStream;
import java.util.stream.Stream;

public interface IOList<T> extends IterablePP<T>{
	
	long size();
	
	default T getUnsafe(long index){
		try{
			return get(index);
		}catch(IOException e){
			throw new RuntimeException(e);
		}
	}
	
	T get(long index) throws IOException;
	void set(long index, T value) throws IOException;
	
	void add(T value) throws IOException;
	
	default Stream<T> stream(){
		return LongStream.range(0, size()).mapToObj(this::getUnsafe);
	}
	
	@Override
	default Iterator<T> iterator(){
		return stream().iterator();
	}
}
