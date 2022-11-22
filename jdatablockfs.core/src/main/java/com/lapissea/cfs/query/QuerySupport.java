package com.lapissea.cfs.query;

import java.io.IOException;
import java.util.HashSet;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

public enum QuerySupport{
	;
	
	public interface Accessor<T>{
		T get(boolean full) throws IOException;
	}
	
	public interface DIter<T>{
		Optional<Accessor<T>> next() throws IOException;
	}
	
	public interface Data<T>{
		
		
		Class<T> elementType();
		
		OptionalLong count();
		
		DIter<T> elements(Set<String> readFields);
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
			while(es.next().isPresent()){
				count++;
			}
			return count;
		}
		@Override
		public Optional<T> any() throws IOException{
			var es   =data.elements(Set.of());
			var match=es.next();
			if(match.isPresent()){
				return Optional.of(match.get().get(true));
			}
			return Optional.empty();
		}
		@Override
		public Query<T> filter(QueryCheck check, Object... args){
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
				public DIter<T> elements(Set<String> readFields){
					var         names=check.fieldNames();
					Set<String> allReadFields;
					merge:
					{
						if(names.size()>readFields.size()){
							if(names.containsAll(readFields)){
								allReadFields=names;
								break merge;
							}
						}else{
							if(readFields.containsAll(names)){
								allReadFields=readFields;
								break merge;
							}
						}
						allReadFields=new HashSet<>(names);
						allReadFields.addAll(readFields);
					}
					
					var src=data.elements(allReadFields);
					return ()->{
						while(true){
							var candidate=src.next();
							if(candidate.isEmpty()) return Optional.empty();
							var acc    =candidate.get();
							var partial=acc.get(false);
							if(ReflectionExecutor.executeCheck(new QueryContext(args, partial), check)){
								return Optional.of(acc);
							}
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
