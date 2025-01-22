package com.lapissea.dfs.query;


import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.utils.iterableplus.Iters;

import java.io.IOException;
import java.util.List;
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
		public QueryableData.QuerySource<T> open(QueryFields queryFields) throws IOException{
			return data.openQuery(queryFields);
		}
	}
	
	public abstract static class TestedQuery<T> implements Query<T>{
		
		protected final Query<T> parent;
		
		public TestedQuery(Query<T> parent){
			this.parent = Objects.requireNonNull(parent);
		}
		
		@Override
		public QueryableData.QuerySource<T> open(QueryFields queryFields) throws IOException{
			addFields(queryFields);
			var source = parent.open(queryFields);
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
						
						if(!test(fe)){
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
		
		protected abstract void addFields(QueryFields queryFields);
		
		protected abstract boolean test(T fe);
	}
	
	public static class Filtered<T> extends TestedQuery<T>{
		
		private final List<Test<T>>       tests;
		private final List<IOField<?, ?>> fields;
		
		public Filtered(Query<T> parent, List<Test<T>> tests){
			super(parent);
			this.tests = List.copyOf(tests);
			fields = Iters.from(this.tests).flatMap(Test::reportFields).toList();
		}
		
		@Override
		protected void addFields(QueryFields queryFields){
			queryFields.add(fields);
		}
		
		@Override
		protected boolean test(T fe){
			for(var test : tests){
				if(!test.test(fe)){
					return false;
				}
			}
			return true;
		}
		
		@Override
		public Query<T> where(List<Test<T>> tests){
			return new Filtered<>(parent, Iters.concat(this.tests, tests).toList());
		}
	}
	
	public static class FilterGeneric<T> extends TestedQuery<T>{
		
		private final Predicate<T> match;
		
		public FilterGeneric(Query<T> parent, Predicate<T> match){
			super(parent);
			this.match = Objects.requireNonNull(match);
		}
		
		@Override
		protected void addFields(QueryFields queryFields){
			queryFields.markUnknown();
		}
		@Override
		protected boolean test(T fe){
			return match.test(fe);
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
		public QueryableData.QuerySource<R> open(QueryFields queryFields) throws IOException{
			var parentFields = new QueryFields();
			parentFields.add(mapper);
			var parent = this.parent.open(parentFields);
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
		public QueryableData.QuerySource<R> open(QueryFields queryFields) throws IOException{
			var parent = this.parent.open(queryFields);
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
	
	public static class Limited<T> implements Query<T>{
		
		private final Query<T> parent;
		private final long     maxCount;
		
		public Limited(Query<T> parent, long maxCount){
			this.parent = Objects.requireNonNull(parent);
			this.maxCount = maxCount;
			if(maxCount<0) throw new IllegalArgumentException("maxCount cannot be negative");
		}
		
		@Override
		public QueryableData.QuerySource<T> open(QueryFields queryFields) throws IOException{
			var parent = this.parent.open(queryFields);
			return new QueryableData.QuerySource<>(){
				private long count = 0;
				@Override
				public void close() throws IOException{
					parent.close();
				}
				@Override
				public boolean step() throws IOException{
					if(count == maxCount) return false;
					count++;
					return parent.step();
				}
				@Override
				public T fullEntry() throws IOException{
					return parent.fullEntry();
				}
				@Override
				public T fieldEntry() throws IOException{
					return parent.fieldEntry();
				}
			};
		}
	}
	
}
