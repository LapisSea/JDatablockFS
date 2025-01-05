package com.lapissea.dfs.query;

import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.utils.iterableplus.Match;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;

public interface Query<T>{
	
	default List<T> allToList() throws IOException{
		var res = new ArrayList<T>();
		try(var data = open(new QueryFields())){
			while(data.step()){
				var full = data.fullEntry();
				res.add(full);
			}
		}
		return res;
	}
	
	default Match<T> firstM() throws IOException{ return Match.from(first()); }
	default Optional<T> first() throws IOException{
		try(var data = open(new QueryFields())){
			while(data.step()){
				var full = data.fullEntry();
				if(full == null) continue;
				return Optional.of(full);
			}
		}
		return Optional.empty();
	}
	default T firstNullable() throws IOException{
		try(var data = open(new QueryFields())){
			if(data.step()){
				return data.fullEntry();
			}
		}
		return null;
	}
	
	default long count() throws IOException{
		long count = 0;
		try(var data = open(new QueryFields())){
			while(data.step()){
				count++;
			}
		}
		return count;
	}
	
	default <V> Query<T> byFieldNEq(Struct.FieldRef<T, V> ref, V needle){
		return byField(ref, hay -> !Objects.equals(hay, needle));
	}
	default <V> Query<T> byFieldEq(Struct.FieldRef<T, V> ref, V needle){
		return byField(ref, hay -> Objects.equals(hay, needle));
	}
	@SuppressWarnings({"unchecked", "rawtypes"})
	default <V> Query<T> byField(Struct.FieldRef<T, V> ref, Predicate<V> match){
		var field = QuerySupport.asIOField((Struct.FieldRef)ref);
		return new Queries.ByField(this, field, match);
	}
	@SuppressWarnings({"unchecked", "rawtypes"})
	default <V1, V2> Query<T> byFields(Struct.FieldRef<T, V1> ref1, Struct.FieldRef<T, V2> ref2, BiPredicate<V1, V2> match){
		var f1 = QuerySupport.asIOField((Struct.FieldRef)ref1);
		var f2 = QuerySupport.asIOField((Struct.FieldRef)ref2);
		return this.byFields(List.of(ref1, ref2), el -> match.test((V1)f1.get(null, (IOInstance)el), (V2)f2.get(null, (IOInstance)el)));
	}
	@SuppressWarnings({"unchecked", "rawtypes"})
	default Query<T> byFields(List<Struct.FieldRef<T, ?>> refs, Predicate<T> match){
		var fields = new ArrayList<IOField>(refs.size());
		for(Struct.FieldRef<T, ?> ref : refs){
			fields.add(QuerySupport.asIOField((Struct.FieldRef)ref));
		}
		return new Queries.ByFields(this, List.of(), fields, List.of(match));
	}
	
	/**
	 * Maps the query results by a specific field.
	 * Using this over {@link Query#mapAny} has the benefit of the query being aware you need only this field.
	 * It will not read any irrelevant fields.
	 *
	 * @param ref reference to the field. Eg: <code>Foobar::foo</code>
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	default <R> Query<R> mapF(Struct.FieldRef<T, R> ref){
		var field = QuerySupport.asIOField((Struct.FieldRef)ref);
		return new Queries.FieldMapped(this, field);
	}
	
	/**
	 * Maps the query results by an arbitrary lambda.
	 * This will require the entire element.
	 * If possible, use {@link Query#mapF}.
	 *
	 * @param mapper the function to transform the query contents by
	 */
	default <R> Query<R> mapAny(Function<T, R> mapper){
		return new Queries.Mapped<>(this, mapper);
	}
	
	QueryableData.QuerySource<T> open(QueryFields queryFields) throws IOException;
}
