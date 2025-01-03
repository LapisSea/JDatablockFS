package com.lapissea.dfs.query;

import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.utils.iterableplus.Match;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

public interface Query<SrcT, T>{
	
	static <T extends IOInstance<T>, V> Query<T, T> byFieldNEq(Struct.FieldRef<T, V> ref, V needle){
		return byField(ref, hay -> !Objects.equals(hay, needle));
	}
	static <T extends IOInstance<T>, V> Query<T, T> byFieldEq(Struct.FieldRef<T, V> ref, V needle){
		return byField(ref, hay -> Objects.equals(hay, needle));
	}
	static <T extends IOInstance<T>, V> Query<T, T> byField(Struct.FieldRef<T, V> ref, Predicate<V> match){
		var field = QuerySupport.asIOField(ref);
		return new Queries.ByField<>(field, match);
	}
	
	Optional<T> findFirst(QueryableData<SrcT> data) throws IOException;
	default Match<T> findFirstM(QueryableData<SrcT> data) throws IOException{ return Match.from(findFirst(data)); }
	
	default <R> Query<SrcT, R> map(Function<T, R> val){
		return new Query<>(){
			@Override
			public Optional<R> findFirst(QueryableData<SrcT> data) throws IOException{
				return Query.this.findFirst(data).map(val);
			}
		};
	}
}
