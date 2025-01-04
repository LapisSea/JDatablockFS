package com.lapissea.dfs.query;


import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.field.FieldSet;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.utils.iterableplus.Iters;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
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
		public QueryableData.QuerySource<T> open(FieldNames fieldNames){
			return data.openQuery(fieldNames);
		}
	}
	
	public abstract static class FieldTested<T extends IOInstance<T>> implements Query<T>{
		
		protected final Query<T> parent;
		
		public FieldTested(Query<T> parent){
			this.parent = Objects.requireNonNull(parent);
		}
		
		@Override
		public QueryableData.QuerySource<T> open(FieldNames fieldNames){
			var source = parent.open(addNames(fieldNames));
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
		
		protected abstract FieldNames addNames(FieldNames fieldNames);
		
		protected abstract boolean test(T fe);
	}
	
	public static class ByField<T extends IOInstance<T>, VT> extends FieldTested<T>{
		
		private final IOField<T, VT> field;
		private final Predicate<VT>  match;
		
		public ByField(Query<T> parent, IOField<T, VT> field, Predicate<VT> match){
			super(parent);
			this.field = Objects.requireNonNull(field);
			this.match = Objects.requireNonNull(match);
		}
		
		@Override
		protected FieldNames addNames(FieldNames fieldNames){
			return fieldNames.add(field);
		}
		@Override
		protected boolean test(T fe){
			var val = field.get(null, fe);
			return match.test(val);
		}
		
		@Override
		public <V> Query<T> byField(Struct.FieldRef<T, V> ref, Predicate<V> match){
			//noinspection unchecked
			var field = (IOField<T, V>)QuerySupport.asIOField(ref);
			return new ByFields<>(
				parent,
				List.of(
					new ByFields.Pair<>(this.field, this.match),
					new ByFields.Pair<>(field, match)
				),
				List.of(), List.of()
			);
		}
		
		@Override
		@SuppressWarnings({"unchecked", "rawtypes"})
		public Query<T> byFields(List<Struct.FieldRef<T, ?>> refs, Predicate<T> match){
			var fields = new ArrayList<IOField>(refs.size() + 1);
			for(Struct.FieldRef<T, ?> ref : refs){
				fields.add(QuerySupport.asIOField((Struct.FieldRef)ref));
			}
			
			return new Queries.ByFields(
				parent,
				List.of(new ByFields.Pair<>(this.field, this.match)),
				fields, List.of(match)
			);
		}
	}
	
	public static class ByFields<T extends IOInstance<T>> extends FieldTested<T>{
		
		public record Pair<T extends IOInstance<T>, VT>(IOField<T, VT> field, Predicate<VT> fieldTest){
			boolean test(T el){
				var val = field.get(null, el);
				return fieldTest.test(val);
			}
		}
		
		private final List<Pair<T, ?>> fieldPairs;
		
		private final FieldSet<T>        fields;
		private final List<Predicate<T>> elementTests;
		
		public ByFields(Query<T> parent, List<Pair<T, ?>> fieldPairs, Collection<IOField<T, ?>> fields, List<Predicate<T>> elementTests){
			super(parent);
			this.fieldPairs = List.copyOf(fieldPairs);
			this.elementTests = List.copyOf(elementTests);
			this.fields = FieldSet.of(Iters.concat(fields, Iters.from(fieldPairs).map(Pair::field)));
		}
		
		@Override
		protected FieldNames addNames(FieldNames fieldNames){
			return fieldNames.add(fields);
		}
		@Override
		protected boolean test(T fe){
			for(var pair : fieldPairs){
				if(!pair.test(fe)){
					return false;
				}
			}
			for(var elT : elementTests){
				if(!elT.test(fe)){
					return false;
				}
			}
			return true;
		}
		
		@Override
		public <V> Query<T> byField(Struct.FieldRef<T, V> ref, Predicate<V> match){
			//noinspection unchecked
			var field = (IOField<T, V>)QuerySupport.asIOField(ref);
			return new ByFields<>(
				parent,
				Iters.concatN1(fieldPairs, new ByFields.Pair<>(field, match)).toList(),
				fields, elementTests
			);
		}
		
		@Override
		@SuppressWarnings({"unchecked", "rawtypes"})
		public Query<T> byFields(List<Struct.FieldRef<T, ?>> refs, Predicate<T> match){
			var fields = new ArrayList<IOField>(refs.size() + this.fields.size());
			fields.addAll(this.fields);
			for(Struct.FieldRef<T, ?> ref : refs){
				fields.add(QuerySupport.asIOField((Struct.FieldRef)ref));
			}
			
			return new Queries.ByFields(
				parent,
				fieldPairs,
				fields, Iters.concatN1(elementTests, match).toList()
			);
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
