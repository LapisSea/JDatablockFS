package com.lapissea.cfs.utils;

import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public interface IterablePP<T> extends Iterable<T>{
	
	default Stream<T> stream(){
		return StreamSupport.stream(spliterator(), false);
	}
	
	default Optional<T> first(){
		var iter = iterator();
		if(iter.hasNext()) return Optional.ofNullable(iter.next());
		return Optional.empty();
	}
	
	default IterablePP<T> filtered(Predicate<T> filter){
		var that = this;
		return () -> new Iterator<T>(){
			final Iterator<T> src = that.iterator();
			
			T next;
			boolean hasData;
			
			void calcNext(){
				while(src.hasNext()){
					T t = src.next();
					if(filter.test(t)){
						next = t;
						hasData = true;
						return;
					}
				}
			}
			
			@Override
			public boolean hasNext(){
				if(!hasData) calcNext();
				return hasData;
			}
			@Override
			public T next(){
				if(!hasData){
					calcNext();
					if(!hasData) throw new NoSuchElementException();
				}
				try{
					return next;
				}finally{
					next = null;
					hasData = false;
				}
			}
		};
	}
	
	default <L> IterablePP<L> flatArray(Function<T, L[]> flatten){
		return flatMap(e -> Arrays.asList(flatten.apply(e)).iterator());
	}
	default <L> IterablePP<L> flatData(Function<T, Iterable<L>> flatten){
		return flatMap(e -> flatten.apply(e).iterator());
	}
	default <L> IterablePP<L> flatMap(Function<T, Iterator<L>> flatten){
		var that = this;
		return () -> new Iterator<L>(){
			final Iterator<T> src = that.iterator();
			
			Iterator<L> flat;
			
			L next;
			boolean hasData;
			
			void doNext(){
				while(true){
					if(flat == null || !flat.hasNext()){
						if(!src.hasNext()) return;
						flat = flatten.apply(src.next());
						continue;
					}
					next = flat.next();
					hasData = true;
					break;
				}
			}
			
			@Override
			public boolean hasNext(){
				if(!hasData) doNext();
				return hasData;
			}
			@Override
			public L next(){
				if(!hasData){
					doNext();
					if(!hasData) throw new NoSuchElementException();
				}
				try{
					return next;
				}finally{
					hasData = false;
					next = null;
				}
			}
		};
	}
	
	default <L> IterablePP<L> map(Function<T, L> mapper){
		var that = this;
		return () -> new Iterator<>(){
			final Iterator<T> src = that.iterator();
			
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
	default IterablePP<T> skip(int count){
		var that = this;
		return () -> {
			var iter = that.iterator();
			for(int i = 0; i<count; i++){
				if(!iter.hasNext()) break;
				iter.next();
			}
			return iter;
		};
	}
	
	static <T> IterablePP<T> nullTerminated(Supplier<Supplier<T>> supplier){
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
}
