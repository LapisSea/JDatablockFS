package com.lapissea.cfs.query;

import com.lapissea.cfs.OptionalPP;
import com.lapissea.cfs.Utils;
import com.lapissea.cfs.objects.collections.IOIterator;
import com.lapissea.util.NotImplementedException;

import java.io.IOException;
import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Collectors;

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
			this.data = data;
		}
		
		@Override
		public Class<T> elementType(){
			return data.elementType();
		}
		@Override
		public long count() throws IOException{
			var c = data.count();
			if(c.isPresent()) return c.getAsLong();
			long count = 0;
			var  es    = data.elements(Set.of());
			while(es.next() != null){
				count++;
			}
			return count;
		}
		@Override
		public boolean anyMatch() throws IOException{
			var c = data.count();
			if(c.isPresent()) return c.getAsLong()>0;
			return data.elements(Set.of()).next() != null;
		}
		@Override
		public OptionalPP<T> any() throws IOException{
			var es    = data.elements(Set.of());
			var match = es.next();
			if(match != null){
				return OptionalPP.of(match.get(true));
			}
			return OptionalPP.empty();
		}
		
		@Override
		public IOIterator<T> all(){
			var elements = data.elements(Set.of());
			return new IOIterator<>(){
				private Accessor<T> acc;
				private boolean run = true;
				
				private void run() throws IOException{
					if(run){
						run = false;
						acc = elements.next();
					}
				}
				
				@Override
				public boolean hasNext() throws IOException{
					run();
					return acc != null;
				}
				@Override
				public T ioNext() throws IOException{
					run();
					if(acc == null){
						throw new NoSuchElementException();
					}
					var t = acc.get(true);
					run = true;
					return t;
				}
			};
		}
		@Override
		public long deleteAll() throws IOException{
			throw NotImplementedException.infer();//TODO: implement StagedQuery.deleteAll()
		}
		
		
		private static final class MappedData<T> implements Data<T>{
			private final Data<?>          base;
			private final Class<T>         elementType;
			private final QueryValueSource source;
			private final Object[]         args;
			private final Set<String>      fieldNames;
			
			private MappedData(Data<?> base, QueryValueSource source, Object[] args){
				this.base = base;
				this.elementType = Objects.requireNonNull((Class<T>)source.type());
				this.source = source;
				this.args = args;
				fieldNames = source.deep()
				                   .filter(QueryValueSource.Field.class::isInstance)
				                   .map(QueryValueSource.Field.class::cast)
				                   .map(QueryValueSource.Field::name)
				                   .collect(Collectors.toSet());
			}
			
			@Override
			public Class<T> elementType(){ return elementType; }
			@Override
			public OptionalLong count(){ return OptionalLong.empty(); }
			
			@Override
			public AccessIterator<T> elements(Set<String> readFields){
				var fields = Utils.join(readFields, fieldNames);
				var src    = base.elements(fields);
				return () -> {
					var acc = src.next();
					if(acc == null) return null;
					return full -> {
						var val = acc.get(full);
						return (T)QueryExecutor.getValueDef(new QueryContext(args, val), source);
					};
				};
			}
		}
		
		@Override
		public <R> Query<R> map(QueryValueSource field, Object... args){
			return new StagedQuery<>(new MappedData<>(data, field, args));
		}
		
		private static final class FilteredData<T> implements Data<T>{
			private final Data<T>    base;
			private final Class<T>   elementType;
			private final QueryCheck check;
			private final Object[]   args;
			
			private FilteredData(Data<T> base, QueryCheck check, Object[] args){
				this.base = base;
				this.elementType = base.elementType();
				this.check = check;
				this.args = args;
			}
			
			@Override
			public Class<T> elementType(){ return elementType; }
			@Override
			public OptionalLong count(){ return OptionalLong.empty(); }
			
			@Override
			public AccessIterator<T> elements(Set<String> readFields){
				var chFields = check.fieldNames();
				var fields   = Utils.join(chFields, readFields);
				var src      = base.elements(fields);
				return () -> {
					while(true){
						var acc = src.next();
						if(acc == null) return null;
						var partial = acc.get(false);
						if(QueryExecutor.executeCheckDef(new QueryContext(args, partial), check)){
							return acc;
						}
					}
				};
			}
		}
		
		@Override
		public Query<T> filter(QueryCheck check, Object... args){
			if(data instanceof StagedQuery.FilteredData<T> dat &&
			   Arrays.equals(args, dat.args)){
				var l   = QueryCheck.uncache(dat.check);
				var r   = QueryCheck.uncache(check);
				var and = QueryCheck.cached(new QueryCheck.And(l, r));
				return new StagedQuery<>(new FilteredData<>(data, and, args));
			}
			return new StagedQuery<>(new FilteredData<>(data, check, args));
		}
	}
	
	public static <T> Query<T> of(Data<T> data){
		return new StagedQuery<>(data);
	}
	
}
