package com.lapissea.dfs.query;

import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.utils.iterableplus.Match;

import java.io.IOException;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

public interface Query<T>{
	
	class FieldNames{
		
		private final HashSet<String> names = new HashSet<>(2);
		
		public <T extends IOInstance<T>> FieldNames add(IOField<T, ?> field){
			return add(field.getName());
		}
		public FieldNames add(String name){
			names.add(name);
			return this;
		}
		
		public boolean isEmpty(){ return names.isEmpty(); }
		
		public Set<String> set(){ return names; }
	}
	
	default Match<T> firstM() throws IOException{ return Match.from(first()); }
	default Optional<T> first() throws IOException{
		try(var data = open(new FieldNames())){
			while(data.step()){
				var full = data.fullEntry();
				if(full == null) continue;
				return Optional.of(full);
			}
		}
		return Optional.empty();
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
	
	QueryableData.QuerySource<T> open(FieldNames fieldNames);
}
