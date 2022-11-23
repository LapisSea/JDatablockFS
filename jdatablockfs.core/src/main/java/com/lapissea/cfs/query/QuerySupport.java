package com.lapissea.cfs.query;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.objects.collections.IOIterator;

import java.io.IOException;
import java.util.*;

public enum QuerySupport{
	;
	
	public interface Accessor<T>{
		T get(boolean full) throws IOException;
	}
	
	public interface AccessIterator<T>{
		Accessor<T> next() throws IOException;
	}
	
	public interface Data<T>{
		
		Class<T> elementType();
		
		OptionalLong count();
		
		AccessIterator<T> elements(Set<String> readFields);
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
		public long count() throws IOException{
			var c=data.count();
			if(c.isPresent()) return c.getAsLong();
			long count=0;
			var  es   =data.elements(Set.of());
			while(es.next()!=null){
				count++;
			}
			return count;
		}
		@Override
		public Optional<T> any() throws IOException{
			var es   =data.elements(Set.of());
			var match=es.next();
			if(match!=null){
				return Optional.of(match.get(true));
			}
			return Optional.empty();
		}
		
		@Override
		public IOIterator<T> all(){
			var elements=data.elements(Set.of());
			return new IOIterator<>(){
				private Accessor<T> acc;
				private boolean first=true;
				
				private void first() throws IOException{
					if(first){
						first=false;
						acc=elements.next();
					}
				}
				
				@Override
				public boolean hasNext() throws IOException{
					first();
					return acc!=null;
				}
				@Override
				public T ioNext() throws IOException{
					first();
					if(acc==null){
						throw new NoSuchElementException();
					}
					var t=acc.get(true);
					acc=elements.next();
					return t;
				}
			};
		}
		
		private static final class FilteredData<T> implements Data<T>{
			private final Data<T>    base;
			private final Class<T>   elementType;
			private final QueryCheck check;
			private final Object[]   args;
			
			private FilteredData(Data<T> base, QueryCheck check, Object[] args){
				this.base=base;
				this.elementType=base.elementType();
				this.check=check;
				this.args=args;
			}
			
			@Override
			public Class<T> elementType(){return elementType;}
			@Override
			public OptionalLong count(){return OptionalLong.empty();}
			
			@Override
			public AccessIterator<T> elements(Set<String> readFields){
				var chFields=check.fieldNames();
				var fields  =Utils.join(chFields, readFields);
				var src     =base.elements(fields);
				return ()->{
					while(true){
						var acc=src.next();
						if(acc==null) return null;
						var partial=acc.get(false);
						if(ReflectionExecutor.executeCheck(new QueryContext(args, partial), check)){
							return acc;
						}
					}
				};
			}
		}
		
		@Override
		public Query<T> filter(QueryCheck check, Object... args){
			if(data instanceof StagedQuery.FilteredData<T> dat&&
			   Arrays.equals(args, dat.args)){
				var l  =QueryCheck.uncache(dat.check);
				var r  =QueryCheck.uncache(check);
				var and=QueryCheck.cached(new QueryCheck.And(l, r));
				return new StagedQuery<>(new FilteredData<>(data, and, args));
			}
			return new StagedQuery<>(new FilteredData<>(data, check, args));
		}
	}
	
	public static <T> Query<T> of(Data<T> data){
		return new StagedQuery<>(data);
	}
	
}
