package com.lapissea.cfs.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public interface IterablePP<T> extends Iterable<T>{
	
	default Stream<T> stream(){
		return StreamSupport.stream(spliterator(), false);
	}
	
	default OptionalPP<T> first(){
		var iter = iterator();
		if(iter.hasNext()) return OptionalPP.ofNullable(iter.next());
		return OptionalPP.empty();
	}
	
	default List<T> collectToList(){
		var res = new ArrayList<T>();
		for(T t : this){
			res.add(t);
		}
		return res;
	}
	
	
	default boolean noneMatch(Predicate<T> predicate){
		return !anyMatch(predicate);
	}
	default boolean anyMatch(Predicate<T> predicate){
		for(T t : this){
			if(predicate.test(t)){
				return true;
			}
		}
		return false;
	}
	default boolean allMatch(Predicate<T> predicate){
		for(T t : this){
			if(!predicate.test(t)){
				return false;
			}
		}
		return true;
	}
	
	default IterablePP<T> filtered(Predicate<T> filter){
		return () -> new Iterator<T>(){
			private final Iterator<T> src = IterablePP.this.iterator();
			
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
		return () -> new Iterator<L>(){
			private final Iterator<T> src = IterablePP.this.iterator();
			
			Iterator<L> flat;
			
			private L next;
			private boolean hasData;
			
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
		return () -> new Iterator<>(){
			private final Iterator<T> src = IterablePP.this.iterator();
			
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
	
	default IterablePP<T> limit(int maxLen){
		return () -> new Iterator<>(){
			private final Iterator<T> src = IterablePP.this.iterator();
			private int count;
			
			@Override
			public boolean hasNext(){
				return maxLen>count && src.hasNext();
			}
			@Override
			public T next(){
				count++;
				return src.next();
			}
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
