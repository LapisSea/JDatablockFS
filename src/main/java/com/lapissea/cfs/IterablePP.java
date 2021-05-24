package com.lapissea.cfs;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

public interface IterablePP<T> extends Iterable<T>{
	
	
	default Optional<T> first(){
		var iter=iterator();
		if(iter.hasNext()) return Optional.ofNullable(iter.next());
		return Optional.empty();
	}
	
	default IterablePP<T> filtered(Predicate<T> filter){
		var that=this;
		return ()->new Iterator<T>(){
			final Iterator<T> src=that.iterator();
			
			T next;
			boolean hasData;
			
			void calcNext(){
				while(src.hasNext()){
					T t=src.next();
					if(filter.test(t)){
						next=t;
						hasData=true;
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
					next=null;
					hasData=false;
				}
			}
		};
	}
	
	default <L> IterablePP<L> flatMap(Function<T, Iterator<L>> flatten){
		var that=this;
		return ()->new Iterator<L>(){
			final Iterator<T> src=that.iterator();
			
			Iterator<L> flat;
			
			L next;
			boolean hasData;
			
			void doNext(){
				while(true){
					if(flat==null||!flat.hasNext()){
						if(!src.hasNext()) return;
						flat=flatten.apply(src.next());
						continue;
					}
					next=flat.next();
					hasData=true;
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
					hasData=false;
					next=null;
				}
			}
		};
	}
	static <T> IterablePP<T> nullTerminated(Supplier<Supplier<T>> supplier){
		return ()->new Iterator<T>(){
			final Supplier<T> src=supplier.get();
			T next;
			
			void calcNext(){
				next=src.get();
			}
			
			@Override
			public boolean hasNext(){
				if(next==null) calcNext();
				return next!=null;
			}
			@Override
			public T next(){
				if(next==null){
					calcNext();
					if(next==null) throw new NoSuchElementException();
				}
				try{
					return next;
				}finally{
					next=null;
				}
			}
		};
	}
}
