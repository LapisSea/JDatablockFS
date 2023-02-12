package com.lapissea.cfs.objects.collections;


import com.lapissea.cfs.type.field.annotations.IOValue;

import java.io.IOException;
import java.util.Objects;

@IOValue.OverrideType.DefaultImpl(IOHashSet.class)
public interface IOSet<T>{
	
	boolean add(T value) throws IOException;
	
	/**
	 * Removes the specified element from this set if it is present.
	 * More formally, removes an element {@code e} such that {@code Objects.equals(o, e)},
	 * if this set contains such an element.  Returns {@code true} if
	 * this set contained the element (or equivalently, if this set
	 * changed as a result of the call). (This set will not contain the
	 * element once the call returns.)
	 *
	 * @param value object to be removed from this set, if present
	 * @return {@code true} if the set contained the specified element
	 */
	boolean remove(T value) throws IOException;
	
	default boolean contains(T value) throws IOException{
		if(isEmpty()) return false;
		var iter = iterator();
		while(iter.hasNext()){
			var next = iter.ioNext();
			if(Objects.equals(next, value)){
				return true;
			}
		}
		return false;
	}
	
	long size();
	
	default boolean isEmpty(){
		return size() == 0;
	}
	
	IOIterator<T> iterator();
	
}
