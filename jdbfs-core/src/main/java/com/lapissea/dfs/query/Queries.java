package com.lapissea.dfs.query;


import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.field.IOField;

import java.io.IOException;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

public final class Queries{
	
	public static class All<T> implements Query<T>{
		
		private final QueryableData<T> data;
		
		public All(QueryableData<T> data){
			this.data = data;
		}
		
		@Override
		public QueryableData.QuerySource<T> open(FieldNames fieldNames){
			return data.openQuery(fieldNames);
		}
	}
	
	public static class ByField<T extends IOInstance<T>> implements Query<T>{
		
		private final Query<T>          parent;
		private final IOField<T, ?>     field;
		private final Predicate<Object> match;
		
		public ByField(Query<T> parent, IOField<T, ?> field, Predicate<?> match){
			this.parent = Objects.requireNonNull(parent);
			this.field = Objects.requireNonNull(field);
			//noinspection unchecked
			this.match = (Predicate<Object>)Objects.requireNonNull(match);
		}
		
		@Override
		public QueryableData.QuerySource<T> open(FieldNames fieldNames){
			var source = parent.open(fieldNames.add(field));
			return new QueryableData.QuerySource<>(){
				private T       val;
				private boolean full;
				
				@Override
				public boolean step() throws IOException{
					val = null;
					full = false;
					
					while(source.step()){
						var fe = source.fieldEntry();
						if(fe == null) continue;
						
						var val = field.get(null, fe);
						if(!match.test(val)){
							continue;
						}
						this.val = fe;
						return true;
					}
					return false;
				}
				
				@Override
				public T fullEntry() throws IOException{
					if(!full){
						val = source.fullEntry();
						full = true;
					}
					return val;
				}
				@Override
				public T fieldEntry(){
					return val;
				}
				@Override
				public void close() throws IOException{
					source.close();
				}
			};
		}
	}
	
	public static class FieldMapped<T extends IOInstance<T>, R> implements Query<R>{
		
		private final Query<T>      parent;
		private final IOField<T, R> mapper;
		
		public FieldMapped(Query<T> parent, IOField<T, R> mapper){
			this.parent = Objects.requireNonNull(parent);
			this.mapper = Objects.requireNonNull(mapper);
		}
		
		@Override
		public QueryableData.QuerySource<R> open(FieldNames fieldNames){
			var parent = this.parent.open(fieldNames.add(mapper));
			return new QueryableData.QuerySource<>(){
				@Override
				public void close() throws IOException{
					parent.close();
				}
				@Override
				public boolean step() throws IOException{
					return parent.step();
				}
				@Override
				public R fullEntry() throws IOException{
					return mapper.get(null, parent.fieldEntry());
				}
				@Override
				public R fieldEntry() throws IOException{
					return mapper.get(null, parent.fieldEntry());
				}
			};
		}
	}
	
	public static class Mapped<T, R> implements Query<R>{
		
		private final Query<T>       parent;
		private final Function<T, R> mapper;
		
		public Mapped(Query<T> parent, Function<T, R> mapper){
			this.parent = Objects.requireNonNull(parent);
			this.mapper = Objects.requireNonNull(mapper);
		}
		
		@Override
		public QueryableData.QuerySource<R> open(FieldNames fieldNames){
			var parent = this.parent.open(new FieldNames());
			return new QueryableData.QuerySource<>(){
				@Override
				public void close() throws IOException{
					parent.close();
				}
				@Override
				public boolean step() throws IOException{
					return parent.step();
				}
				@Override
				public R fullEntry() throws IOException{
					return mapper.apply(parent.fullEntry());
				}
				@Override
				public R fieldEntry() throws IOException{
					return mapper.apply(parent.fullEntry());
				}
			};
		}
	}
	
}
