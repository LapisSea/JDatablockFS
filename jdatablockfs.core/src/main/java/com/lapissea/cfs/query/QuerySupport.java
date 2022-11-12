package com.lapissea.cfs.query;

import com.lapissea.util.UtilL;

import java.io.IOException;
import java.util.*;
import java.util.function.Predicate;

public enum QuerySupport{
	;
	
	public interface Accessor<T>{
		T get() throws IOException;
	}
	
	public interface Data<T>{
		Class<T> elementType();
		
		OptionalLong count();
		
		Iterator<Accessor<T>> elements(Set<String> readFields);
	}
	
	private static final class StagedQuery<T> implements Query<T>{
		
		private final Data<T> data;
		private StagedQuery(Data<T> data){
			this.data=data;
		}
		
		@Override
		public Class<T> elementType(){
			return data.elementType();
		}
		@Override
		public long count(){
			var c=data.count();
			if(c.isPresent()) return c.getAsLong();
			long count=0;
			var  es   =data.elements(null);
			while(es.hasNext()){
				es.next();
				count++;
			}
			return count;
		}
		@Override
		public Optional<T> any() throws IOException{
			var es=data.elements(null);
			if(es.hasNext()){
				return Optional.of(es.next().get());
			}
			return Optional.empty();
		}
		@Override
		public Query<T> filter(Set<String> readFields, Predicate<T> filter){
			var type=data.elementType();
			return new StagedQuery<>(new Data<>(){
				@Override
				public Class<T> elementType(){
					return type;
				}
				@Override
				public OptionalLong count(){
					return OptionalLong.empty();
				}
				@Override
				public Iterator<Accessor<T>> elements(Set<String> readFields){
					var src=data.elements(readFields);
					return new Iterator<Accessor<T>>(){
						Accessor<T> next;
						void calcNext(){
							if(next!=null) return;
							while(src.hasNext()){
								T   value;
								var candidate=src.next();
								try{
									value=candidate.get();
								}catch(IOException e){
									throw UtilL.uncheckedThrow(e);
								}
								if(filter.test(value)){
									next=candidate;
									break;
								}
							}
						}
						@Override
						public boolean hasNext(){
							calcNext();
							return next!=null;
						}
						@Override
						public Accessor<T> next(){
							calcNext();
							Objects.requireNonNull(next);
							var n=next;
							next=null;
							return n;
						}
					};
				}
			});
		}
	}
	
	public static <T> Query<T> of(Data<T> data){
		return new StagedQuery<>(data);
	}
	
}
