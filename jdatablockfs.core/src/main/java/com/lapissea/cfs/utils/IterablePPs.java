package com.lapissea.cfs.utils;

import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.Supplier;

public final class IterablePPs{
	
	public static <T> IterablePP<T> nullTerminated(Supplier<Supplier<T>> supplier){
		return () -> new Iterator<T>(){
			final Supplier<T> src = supplier.get();
			T next;
			
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
	
	public static <T> IterablePP<T> of(Collection<T> data){
		return data::iterator;
	}
}
